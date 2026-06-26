package com.codebasemanager.repositoryscan.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for parsing a repository that already exists on local disk.
 */
public record ParseRepositoryRequest(
		@NotBlank String path,
		String name,
		String url) {
}
