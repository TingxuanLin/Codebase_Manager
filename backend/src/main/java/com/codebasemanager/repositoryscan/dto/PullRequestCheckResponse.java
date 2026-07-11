package com.codebasemanager.repositoryscan.dto;

import java.util.List;

/**
 * Response body for checking open pull requests for one repository.
 */
public record PullRequestCheckResponse(
		Long repositoryId,
		String repositoryUrl,
		String baseBranch,
		int openPullRequestCount,
		int newPullRequestCount,
		List<PullRequestSummaryResponse> pullRequests) {
}
