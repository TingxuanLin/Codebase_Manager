package com.codebasemanager.repositoryscan.dto;

/**
 * Response body for one changed file in a branch comparison.
 */
public record FileChangeResponse(
		String path,
		String oldPath,
		String changeType,
		int additions,
		int deletions) {
}
