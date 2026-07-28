package com.codebasemanager.repositoryscan.dto;

/**
 * Response body for one rule-based pull request risk finding.
 */
public record PullRequestRiskFindingResponse(
		String category,
		String severity,
		int points,
		String path,
		String reason,
		String evidence) {
}
