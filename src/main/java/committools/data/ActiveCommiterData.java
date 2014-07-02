/**
 *
 */
package committools.data;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.logging.Logger;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.revwalk.RevCommit;

import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;

/**
 * Extract the active committers through time from a single Git repository
 *
 * @author Miltos Allamanis <m.allamanis@ed.ac.uk>
 *
 */
public class ActiveCommiterData {

	private static class CommiterData {
		final Range<Integer> commitRange;
		final double maxTimeBetweenCommits;

		public CommiterData(final List<Integer> commitTimes) {
			checkArgument(!commitTimes.isEmpty());
			Collections.sort(commitTimes);
			final int startTime = commitTimes.get(0);
			final int stopTime = commitTimes.get(commitTimes.size() - 1);
			commitRange = Range.closed(startTime, stopTime);
			int maxGap = 0;
			for (int i = 1; i < commitTimes.size(); i++) {
				final int gap = commitTimes.get(i) - commitTimes.get(i - 1);
				if (gap > maxGap) {
					maxGap = gap;
				}
			}
			maxTimeBetweenCommits = maxGap;
		}
	}

	/**
	 * Return a map with the last commit of each user giving a grace period
	 * around the last commit seen in allCommits.
	 *
	 * @param allCommits
	 * @param activeGracePeriod
	 * @return
	 */
	public static Map<GitCommiterIdentity, Range<Integer>> getCommitActivityTimePerUser(
			final SortedMap<Integer, RevCommit> allCommits,
			final int activeGracePeriod) {
		final Map<GitCommiterIdentity, Range<Integer>> commitRanges = Maps
				.newHashMap();
		// Get last commit time for all users and maxTime
		int maxTime = 0;
		for (final Map.Entry<Integer, RevCommit> commit : allCommits.entrySet()) {
			final GitCommiterIdentity identity = new GitCommiterIdentity(commit
					.getValue().getAuthorIdent());
			Range<Integer> commitRange = commitRanges.get(identity);
			if (commitRange == null) {
				commitRange = Range.closed(commit.getValue().getCommitTime(),
						commit.getValue().getCommitTime() + 1);
				commitRanges.put(identity, commitRange);
			}
			final int lastTime = commitRange.upperEndpoint();
			final int commitTime = commit.getValue().getCommitTime();
			if (lastTime < commitTime) {
				commitRange = Range.closed(commitRange.lowerEndpoint(),
						commitTime);
				commitRanges.put(identity, commitRange);
			}
			if (commitTime > maxTime) {
				maxTime = commitTime;
			}
		}

		// Post-process, giving a grace time of the last 50 days
		final int graceTime = maxTime - activeGracePeriod;
		for (final Map.Entry<GitCommiterIdentity, Range<Integer>> entry : commitRanges
				.entrySet()) {
			if (entry.getValue().upperEndpoint() > graceTime) {
				entry.setValue(Range.closed(entry.getValue().lowerEndpoint(),
						Integer.MAX_VALUE));
			}
		}
		return commitRanges;
	}

	/**
	 * @param args
	 * @throws GitAPIException
	 * @throws IOException
	 * @throws NoHeadException
	 */
	public static void main(final String[] args) throws NoHeadException,
			IOException, GitAPIException {
		if (args.length != 2) {
			System.err.println("Usage single|multiple <directory>");
			System.exit(-1);
		}
		if (args[0].equals("single")) {
			final ActiveCommiterData acd = new ActiveCommiterData();
			acd.buildData(args[1]);
			acd.printTimeSeries();
			System.out.println("Activity Ratio: " + acd.getLastActivityRatio());
		} else if (args[0].equals("multiple")) {
			File projectsDir = new File(args[1]);
			checkArgument(projectsDir.isDirectory());
			for (final File project : projectsDir.listFiles()) {
				try {
					final ActiveCommiterData acd = new ActiveCommiterData();
					acd.buildData(project.getAbsolutePath());
					System.out
							.println(project.getName()
									+ ","
									+ String.format("%.4f",
											acd.getLastActivityRatio()));
				} catch (Throwable e) {
					LOGGER.warning("Failed to extract information for "
							+ project);
				}
			}
		} else {
			throw new IllegalArgumentException("unrecognized parameter "
					+ args[0]);
		}
	}

	private static final Logger LOGGER = Logger
			.getLogger(ActiveCommiterData.class.getName());

	private static final int FOUR_MONTHS = 60 * 60 * 24 * 30 * 4;

	final RangeMap<Integer, Integer> numActiveCommiters = TreeRangeMap.create();

	public void buildData(final String gitDirectory) throws NoHeadException,
	IOException, GitAPIException {
		final SortedMap<Integer, RevCommit> allCommits = GitCommitUtils
				.getCommitsWithTime(GitCommitUtils
						.getGitRepository(gitDirectory));
		final Integer startTime = allCommits.firstKey();
		final Range<Integer> activityPeriod = Range.closed(startTime,
				allCommits.lastKey());

		final Map<GitCommiterIdentity, Range<Integer>> commitTimeRanges = getCommitActivityTimePerUser(
				allCommits, FOUR_MONTHS);

		// Split period into 6 months chunks
		final int nChunks = (int) Math.ceil(((double) activityPeriod
				.upperEndpoint() - activityPeriod.lowerEndpoint())
				/ FOUR_MONTHS);

		for (int i = 0; i < nChunks; i++) {
			final Range<Integer> currentPeriod = Range.closedOpen(startTime + i
					* FOUR_MONTHS, startTime + (i + 1) * FOUR_MONTHS);
			int nActiveCommiters = 0;
			for (final Range<Integer> userActivityPeriod : commitTimeRanges
					.values()) {
				if (userActivityPeriod.isConnected(currentPeriod)) {
					nActiveCommiters++;
				}
			}
			numActiveCommiters.put(currentPeriod, nActiveCommiters);
		}
	}

	public double getLastActivityRatio() {
		int max = 0;
		for (final int activeCommiters : numActiveCommiters.asMapOfRanges()
				.values()) {
			if (max < activeCommiters) {
				max = activeCommiters;
			}
		}
		final int nowActiveCommiters = numActiveCommiters
				.get(numActiveCommiters.span().upperEndpoint() - 1);
		return ((double) nowActiveCommiters) / max;
	}

	public void printTimeSeries() {
		final StringBuffer sb = new StringBuffer();
		for (final int activeCommiters : numActiveCommiters.asMapOfRanges()
				.values()) {
			sb.append(activeCommiters + ",");
		}
		System.out.println(sb.toString());
	}
}
