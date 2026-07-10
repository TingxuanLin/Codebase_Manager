package com.codebasemanager.repositoryscan.dto;

import java.time.OffsetDateTime;

/**
 * Response body for one GitHub pull request discovered for a repository.
 */
public record PullRequestSummaryResponse(
		int number,
		String title,
		String state,
		String baseBranch,
		String headBranch,
		String baseSha,
		String headSha,
		String authorLogin,
		String htmlUrl,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt,
		boolean newlySeen) {
}
