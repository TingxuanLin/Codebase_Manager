package com.codebasemanager.repositoryscan.dto;

/**
 * Response body for one branch available on a remote GitHub repository.
 */
public record GitHubBranchResponse(
		String name,
		String commitSha) {
}
