package com.codebasemanager.repositoryscan.dto;

/**
 * Response body summarizing the database rows and counts created by a parse run.
 */
public record ParseRepositoryResponse(
		Long repositoryId,
		Long branchId,
		Long scanRunId,
		String name,
		String branch,
		String headCommitSha,
		int fileCount,
		int classCount,
		int methodCount) {
}
