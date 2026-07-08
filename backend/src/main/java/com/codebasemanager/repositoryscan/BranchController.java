package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.BranchComparisonResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/repositories/{repositoryId}/branches")
public class BranchController {

	private final RepositoryScanService repositoryScanService;

	/**
	 * Receives the service that owns stored branch queries.
	 */
	public BranchController(RepositoryScanService repositoryScanService) {
		this.repositoryScanService = repositoryScanService;
	}

	/**
	 * Deletes one stored branch and its dependent branch-scoped records.
	 */
	@DeleteMapping("/{branchId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteBranch(
			@PathVariable long repositoryId,
			@PathVariable long branchId) {
		repositoryScanService.deleteBranch(repositoryId, branchId);
	}

	/**
	 * Marks one stored branch as the repository default branch.
	 */
	@PatchMapping("/{branchId}/default")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void setDefaultBranch(
			@PathVariable long repositoryId,
			@PathVariable long branchId) {
		repositoryScanService.setDefaultBranch(repositoryId, branchId);
	}

	/**
	 * Returns the latest stored comparison between this branch and the repository default branch.
	 */
	@GetMapping("/{branchId}/comparison")
	public BranchComparisonResponse getBranchComparison(
			@PathVariable long repositoryId,
			@PathVariable long branchId) {
		return repositoryScanService.getBranchComparison(repositoryId, branchId);
	}
}
