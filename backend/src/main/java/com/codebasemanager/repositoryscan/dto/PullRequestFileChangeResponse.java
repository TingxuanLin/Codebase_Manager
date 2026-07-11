package com.codebasemanager.repositoryscan.dto;

/**
 * Response body for one changed file in a stored pull request diff.
 */
public record PullRequestFileChangeResponse(
		String path,
		String oldPath,
		String changeType,
		int additions,
		int deletions,
		int changes,
		String patch,
		String blobUrl,
		String rawUrl) {
}
