package com.codebasemanager.repositoryscan.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for the latest scan comparison between a branch and repository default branch.
 */
public record BranchComparisonResponse(
		Long repositoryId,
		Long branchId,
		Long scanRunId,
		String branch,
		String defaultBranch,
		String baseCommitSha,
		String headCommitSha,
		OffsetDateTime scannedAt,
		int changedFileCount,
		int additions,
		int deletions,
		List<FileChangeResponse> changes) {
}
