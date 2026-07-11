package com.codebasemanager.repositoryscan.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for a pull request risk analysis.
 */
public record PullRequestRiskResponse(
		long repositoryId,
		int pullRequestNumber,
		String title,
		String state,
		String baseBranch,
		String headBranch,
		String baseSha,
		String headSha,
		int score,
		String severity,
		OffsetDateTime generatedAt,
		int changedFileCount,
		int additions,
		int deletions,
		List<PullRequestRiskFindingResponse> findings) {
}
