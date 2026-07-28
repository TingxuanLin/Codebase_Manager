package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.BranchComparisonResponse;
import com.codebasemanager.repositoryscan.dto.BranchSummaryResponse;
import java.util.List;
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

	private final BranchScanService branchScanService;

	/**
	 * Receives the service that owns stored branch queries.
	 */
	public BranchController(BranchScanService branchScanService) {
		this.branchScanService = branchScanService;
	}

	/**
	 * Lists all stored branches for a repository.
	 */
	@GetMapping
	public List<BranchSummaryResponse> listBranches(@PathVariable long repositoryId) {
		return branchScanService.listBranches(repositoryId);
	}

	/**
	 * Deletes one stored branch and its dependent branch-scoped records.
	 */
	@DeleteMapping("/{branchId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteBranch(
			@PathVariable long repositoryId,
			@PathVariable long branchId) {
		branchScanService.deleteBranch(repositoryId, branchId);
	}

	/**
	 * Marks one stored branch as the repository default branch.
	 */
	@PatchMapping("/{branchId}/default")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void setDefaultBranch(
			@PathVariable long repositoryId,
			@PathVariable long branchId) {
		branchScanService.setDefaultBranch(repositoryId, branchId);
	}

	/**
	 * Returns the latest stored comparison between this branch and the repository default branch.
	 */
	@GetMapping("/{branchId}/comparison")
	public BranchComparisonResponse getBranchComparison(
			@PathVariable long repositoryId,
			@PathVariable long branchId) {
		return branchScanService.getBranchComparison(repositoryId, branchId);
	}
}
