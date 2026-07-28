package com.codebasemanager.repositoryscan.dto;

/**
 * Response body for one stored branch in a repository's branch list.
 */
public record BranchSummaryResponse(
		Long id,
		String name,
		boolean isDefault,
		String lastScannedCommitSha) {
}
