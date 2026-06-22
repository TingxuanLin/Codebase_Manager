package com.codebasemanager.repositoryscan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RepositoryScanException extends RuntimeException {

	/**
	 * Creates a client-facing scan error with a readable message.
	 */
	public RepositoryScanException(String message) {
		super(message);
	}

	/**
	 * Creates a client-facing scan error while preserving the original cause.
	 */
	public RepositoryScanException(String message, Throwable cause) {
		super(message, cause);
	}
}
