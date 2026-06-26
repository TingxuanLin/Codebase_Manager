package com.codebasemanager.repositoryscan.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for parsing a repository by cloning or fetching a GitHub URL.
 */
public record ParseGitHubRepositoryRequest(
		@NotBlank String url,
		String branch,
		String name) {
}
