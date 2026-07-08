package com.codebasemanager.repositoryscan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class RepositoryResourceNotFoundException extends RuntimeException {

	/**
	 * Creates a client-facing not-found error with a readable message.
	 */
	public RepositoryResourceNotFoundException(String message) {
		super(message);
	}
}
