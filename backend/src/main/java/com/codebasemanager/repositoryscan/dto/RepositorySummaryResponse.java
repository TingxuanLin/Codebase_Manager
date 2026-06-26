package com.codebasemanager.repositoryscan.dto;

import java.time.OffsetDateTime;

/**
 * Response body for one repository in the repository list.
 */
public record RepositorySummaryResponse(
		Long id,
		String name,
		String url,
		int branchCount,
		String latestBranch,
		String latestCommitSha,
		Long latestScanRunId,
		String latestScanStatus,
		OffsetDateTime lastScannedAt,
		int fileCount,
		int classCount,
		int methodCount) {
}
