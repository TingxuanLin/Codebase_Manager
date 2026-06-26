package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.ParseGitHubRepositoryRequest;
import com.codebasemanager.repositoryscan.dto.ParseRepositoryRequest;
import com.codebasemanager.repositoryscan.dto.ParseRepositoryResponse;
import com.codebasemanager.repositoryscan.dto.RepositorySummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/repositories")
public class RepositoryScanController {

	private final RepositoryScanService repositoryScanService;

	/**
	 * Receives the service that owns repository parsing and storage.
	 */
	public RepositoryScanController(RepositoryScanService repositoryScanService) {
		this.repositoryScanService = repositoryScanService;
	}

	/**
	 * Lists all repositories stored in the database.
	 */
	@GetMapping
	public List<RepositorySummaryResponse> listRepositories() {
		return repositoryScanService.listRepositories();
	}

	/**
	 * Parses a Git repository that already exists on the backend machine.
	 */
	@PostMapping("/parse-local")
	@ResponseStatus(HttpStatus.CREATED)
	public ParseRepositoryResponse parseLocalRepository(@Valid @RequestBody ParseRepositoryRequest request) {
		return repositoryScanService.parseAndStore(request);
	}

	/**
	 * Clones or fetches a GitHub repository, then parses the checked-out branch.
	 */
	@PostMapping("/parse-github")
	@ResponseStatus(HttpStatus.CREATED)
	public ParseRepositoryResponse parseGitHubRepository(@Valid @RequestBody ParseGitHubRepositoryRequest request) {
		return repositoryScanService.parseGitHubAndStore(request);
	}

	/**
	 * Deletes a repository and its dependent database records.
	 */
	@DeleteMapping("/{repositoryId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRepository(@PathVariable long repositoryId) {
		repositoryScanService.deleteRepository(repositoryId);
	}
}
