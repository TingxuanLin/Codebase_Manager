package com.codebasemanager.repositoryscan.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for a stored pull request and its file-level diff.
 */
public record PullRequestDiffResponse(
		long repositoryId,
		int number,
		String title,
		String state,
		String baseBranch,
		String headBranch,
		String baseSha,
		String headSha,
		OffsetDateTime diffFetchedAt,
		int changedFileCount,
		int additions,
		int deletions,
		List<PullRequestFileChangeResponse> changes) {
}
