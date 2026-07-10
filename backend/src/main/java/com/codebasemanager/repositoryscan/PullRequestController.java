package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.PullRequestCheckResponse;
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

	/**
	 * Receives the service that owns pull request checks and persistence.
	 */
	public PullRequestController(PullRequestService pullRequestService) {
		this.pullRequestService = pullRequestService;
	}

	/**
	 * Lists open pull requests already stored for the repository.
	 */
	@GetMapping
	public List<PullRequestSummaryResponse> listPullRequests(@PathVariable long repositoryId) {
		return pullRequestService.listStoredOpenPullRequests(repositoryId);
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
