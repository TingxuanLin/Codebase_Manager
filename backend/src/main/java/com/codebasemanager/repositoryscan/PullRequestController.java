package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.PullRequestCheckResponse;
import com.codebasemanager.repositoryscan.dto.PullRequestDiffResponse;
import com.codebasemanager.repositoryscan.dto.PullRequestRiskResponse;
import com.codebasemanager.repositoryscan.dto.PullRequestSummaryResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/repositories/{repositoryId}/pull-requests")
public class PullRequestController {

	private final PullRequestService pullRequestService;
	private final PullRequestRiskService pullRequestRiskService;

	/**
	 * Receives the service that owns pull request checks and persistence.
	 */
	public PullRequestController(PullRequestService pullRequestService, PullRequestRiskService pullRequestRiskService) {
		this.pullRequestService = pullRequestService;
		this.pullRequestRiskService = pullRequestRiskService;
	}

	/**
	 * Lists open pull requests already stored for the repository.
	 */
	@GetMapping
	public List<PullRequestSummaryResponse> listPullRequests(@PathVariable long repositoryId) {
		return pullRequestService.listStoredOpenPullRequests(repositoryId);
	}

	/**
	 * Returns the stored file-level diff for one pull request.
	 */
	@GetMapping("/{pullRequestNumber}/diff")
	public PullRequestDiffResponse getPullRequestDiff(
			@PathVariable long repositoryId,
			@PathVariable int pullRequestNumber) {
		return pullRequestService.getStoredPullRequestDiff(repositoryId, pullRequestNumber);
	}

	/**
	 * Calculates and stores rule-based risk findings for one pull request.
	 */
	@GetMapping("/{pullRequestNumber}/risk")
	public PullRequestRiskResponse getPullRequestRisk(
			@PathVariable long repositoryId,
			@PathVariable int pullRequestNumber) {
		return pullRequestRiskService.analyzePullRequestRisk(repositoryId, pullRequestNumber);
	}

	/**
	 * Lists pull requests directly from GitHub without storing them.
	 */
	@GetMapping("/all")
	public List<PullRequestSummaryResponse> listAllPullRequests(@PathVariable long repositoryId) {
		return pullRequestService.listAllPullRequests(repositoryId);
	}

	/**
	 * Checks GitHub for open pull requests targeting the stored repository default branch.
	 */
	@PostMapping("/check")
	public PullRequestCheckResponse checkPullRequests(@PathVariable long repositoryId) {
		return pullRequestService.checkForNewPullRequests(repositoryId);
	}
}
