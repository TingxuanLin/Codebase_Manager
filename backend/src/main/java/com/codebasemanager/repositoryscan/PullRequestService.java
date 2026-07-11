package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.PullRequestCheckResponse;
import com.codebasemanager.repositoryscan.dto.PullRequestSummaryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class PullRequestService {

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final String githubToken = System.getenv("GITHUB_TOKEN");

	/**
	 * Receives the JDBC helper used to read repositories and store discovered pull requests.
	 */
	public PullRequestService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * Lists open pull requests already stored for one repository.
	 */
	@Transactional(readOnly = true)
	public List<PullRequestSummaryResponse> listStoredOpenPullRequests(long repositoryId) {
		ensureRepositoryExists(repositoryId);
		return jdbcTemplate.query("""
				SELECT github_pr_number, title, state, base_branch, head_branch,
				       base_sha, head_sha, author_login, html_url, created_at, updated_at
				FROM pull_requests
				WHERE repository_id = ?
				  AND state = 'open'
				ORDER BY COALESCE(updated_at, created_at) DESC NULLS LAST, github_pr_number DESC
				""", (rs, rowNum) -> new PullRequestSummaryResponse(
				rs.getInt("github_pr_number"),
				rs.getString("title"),
				rs.getString("state"),
				rs.getString("base_branch"),
				rs.getString("head_branch"),
				rs.getString("base_sha"),
				rs.getString("head_sha"),
				rs.getString("author_login"),
				rs.getString("html_url"),
				rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("updated_at", OffsetDateTime.class),
				false), repositoryId);
	}

	/**
	 * Lists all pull requests from GitHub without writing them to the database.
	 */
	public List<PullRequestSummaryResponse> listAllPullRequests(long repositoryId) {
		RepositoryPullRequestTarget target = findRepositoryPullRequestTarget(repositoryId);
		GitHubRepositoryPath repositoryPath = parseGitHubRepositoryPath(target.url());
		return fetchPullRequests(target, repositoryPath, "all");
	}

	/**
	 * Checks GitHub for open pull requests targeting the repository default branch and stores the latest open set.
	 */
	public PullRequestCheckResponse checkForNewPullRequests(long repositoryId) {
		RepositoryPullRequestTarget target = findRepositoryPullRequestTarget(repositoryId);
		GitHubRepositoryPath repositoryPath = parseGitHubRepositoryPath(target.url());
		List<PullRequestSummaryResponse> fetchedPullRequests = fetchPullRequests(target, repositoryPath, "open");
		List<PullRequestSummaryResponse> pullRequests = syncOpenPullRequests(target, fetchedPullRequests);
		int newPullRequestCount = 0;
		for (PullRequestSummaryResponse pullRequest : pullRequests) {
			if (pullRequest.newlySeen()) {
				newPullRequestCount++;
			}
		}
		return new PullRequestCheckResponse(
				repositoryId,
				target.url(),
				target.defaultBranch(),
				pullRequests.size(),
				newPullRequestCount,
				pullRequests);
	}

	private void ensureRepositoryExists(long repositoryId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM repositories WHERE id = ?",
				Integer.class,
				repositoryId);
		if (count == null || count == 0) {
			throw new RepositoryResourceNotFoundException("Repository not found: " + repositoryId);
		}
	}

	private RepositoryPullRequestTarget findRepositoryPullRequestTarget(long repositoryId) {
		try {
			return jdbcTemplate.queryForObject("""
					SELECT r.url,
					       COALESCE(default_branch.name, 'main') AS default_branch
					FROM repositories r
					LEFT JOIN branches default_branch ON default_branch.repository_id = r.id
					    AND default_branch.id = r.default_branch_id
					WHERE r.id = ?
					""", (rs, rowNum) -> new RepositoryPullRequestTarget(
					repositoryId,
					rs.getString("url"),
					rs.getString("default_branch")), repositoryId);
		}
		catch (EmptyResultDataAccessException ex) {
			throw new RepositoryResourceNotFoundException("Repository not found: " + repositoryId);
		}
	}

	private List<PullRequestSummaryResponse> fetchPullRequests(
			RepositoryPullRequestTarget target,
			GitHubRepositoryPath repositoryPath,
			String state) {
		String encodedBaseBranch = URLEncoder.encode(target.defaultBranch(), StandardCharsets.UTF_8);
		String encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8);
		URI uri = URI.create("https://api.github.com/repos/%s/%s/pulls?state=%s&base=%s&per_page=100"
				.formatted(repositoryPath.owner(), repositoryPath.name(), encodedState, encodedBaseBranch));
		List<PullRequestSummaryResponse> pullRequests = new ArrayList<>();

		try {
			URI nextPageUri = uri;
			while (nextPageUri != null) {
				HttpResponse<String> response = sendGitHubGet(nextPageUri);
				JsonNode root = objectMapper.readTree(response.body());
				if (!root.isArray()) {
					throw new RepositoryScanException("GitHub pull request response was not an array.");
				}
				for (JsonNode pullRequestNode : root) {
					pullRequests.add(toPullRequestSummary(pullRequestNode, false));
				}
				nextPageUri = nextPageUri(response);
			}
			return pullRequests;
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to check GitHub pull requests.", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RepositoryScanException("GitHub pull request check was interrupted.", ex);
		}
	}

	private List<PullRequestSummaryResponse> syncOpenPullRequests(
			RepositoryPullRequestTarget target,
			List<PullRequestSummaryResponse> fetchedPullRequests) {
		return transactionTemplate.execute(status -> {
			List<PullRequestSummaryResponse> storedPullRequests = new ArrayList<>();
			for (PullRequestSummaryResponse pullRequest : fetchedPullRequests) {
				storedPullRequests.add(upsertPullRequest(target, pullRequest));
			}
			deletePullRequestsNotInOpenSet(target, storedPullRequests);
			return storedPullRequests;
		});
	}

	private HttpResponse<String> sendGitHubGet(URI uri) throws IOException, InterruptedException {
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "Codebase-Manager")
				.GET();
		if (StringUtils.hasText(githubToken)) {
			requestBuilder.header("Authorization", "Bearer " + githubToken.strip());
		}
		HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new RepositoryScanException("GitHub pull request check failed with status " + response.statusCode() + ": " + response.body());
		}
		return response;
	}

	private URI nextPageUri(HttpResponse<?> response) {
		return response.headers()
				.firstValue("Link")
				.flatMap(this::extractNextPageUri)
				.orElse(null);
	}

	private java.util.Optional<URI> extractNextPageUri(String linkHeader) {
		for (String linkPart : linkHeader.split(",")) {
			String[] sections = linkPart.split(";");
			if (sections.length < 2) {
				continue;
			}
			String uriSection = sections[0].strip();
			boolean next = false;
			for (int index = 1; index < sections.length; index++) {
				if ("rel=\"next\"".equals(sections[index].strip())) {
					next = true;
					break;
				}
			}
			if (next && uriSection.startsWith("<") && uriSection.endsWith(">")) {
				return java.util.Optional.of(URI.create(uriSection.substring(1, uriSection.length() - 1)));
			}
		}
		return java.util.Optional.empty();
	}

	private PullRequestSummaryResponse upsertPullRequest(RepositoryPullRequestTarget target, PullRequestSummaryResponse pullRequest) {
		int number = pullRequest.number();
		boolean newlySeen = !pullRequestExists(target.repositoryId(), number);

		jdbcTemplate.update("""
				INSERT INTO pull_requests
				    (repository_id, github_pr_number, title, state, base_branch, head_branch,
				     base_sha, head_sha, author_login, html_url, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
				ON CONFLICT (repository_id, github_pr_number) DO UPDATE
				SET title = EXCLUDED.title,
				    state = EXCLUDED.state,
				    base_branch = EXCLUDED.base_branch,
				    head_branch = EXCLUDED.head_branch,
				    base_sha = EXCLUDED.base_sha,
				    head_sha = EXCLUDED.head_sha,
				    author_login = EXCLUDED.author_login,
				    html_url = EXCLUDED.html_url,
				    updated_at = EXCLUDED.updated_at,
				    last_seen_at = NOW()
				""", target.repositoryId(), number, pullRequest.title(), pullRequest.state(), pullRequest.baseBranch(), pullRequest.headBranch(),
				pullRequest.baseSha(), pullRequest.headSha(), pullRequest.authorLogin(), pullRequest.htmlUrl(),
				pullRequest.createdAt(), pullRequest.updatedAt());

		return new PullRequestSummaryResponse(
				pullRequest.number(),
				pullRequest.title(),
				pullRequest.state(),
				pullRequest.baseBranch(),
				pullRequest.headBranch(),
				pullRequest.baseSha(),
				pullRequest.headSha(),
				pullRequest.authorLogin(),
				pullRequest.htmlUrl(),
				pullRequest.createdAt(),
				pullRequest.updatedAt(),
				newlySeen);
	}

	private PullRequestSummaryResponse toPullRequestSummary(JsonNode node, boolean newlySeen) {
		return new PullRequestSummaryResponse(
				node.path("number").asInt(),
				node.path("title").asText(),
				node.path("state").asText(),
				node.path("base").path("ref").asText(),
				node.path("head").path("ref").asText(),
				textOrNull(node.path("base").path("sha")),
				textOrNull(node.path("head").path("sha")),
				textOrNull(node.path("user").path("login")),
				node.path("html_url").asText(),
				parseOffsetDateTime(textOrNull(node.path("created_at"))),
				parseOffsetDateTime(textOrNull(node.path("updated_at"))),
				newlySeen);
	}

	private void deletePullRequestsNotInOpenSet(RepositoryPullRequestTarget target, List<PullRequestSummaryResponse> openPullRequests) {
		List<Integer> openNumbers = openPullRequests.stream()
				.map(PullRequestSummaryResponse::number)
				.toList();
		if (openNumbers.isEmpty()) {
			jdbcTemplate.update("DELETE FROM pull_requests WHERE repository_id = ? AND base_branch = ?",
					target.repositoryId(), target.defaultBranch());
			return;
		}
		jdbcTemplate.update("""
				DELETE FROM pull_requests
				WHERE repository_id = ?
				  AND base_branch = ?
				  AND github_pr_number <> ALL (?::int[])
				""", target.repositoryId(), target.defaultBranch(), openNumbers.toArray(Integer[]::new));
	}

	private boolean pullRequestExists(long repositoryId, int number) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM pull_requests WHERE repository_id = ? AND github_pr_number = ?",
				Integer.class,
				repositoryId,
				number);
		return count != null && count > 0;
	}

	private GitHubRepositoryPath parseGitHubRepositoryPath(String url) {
		String normalizedUrl = url.strip();
		if (normalizedUrl.startsWith("git@github.com:")) {
			String path = normalizedUrl.substring("git@github.com:".length());
			return parseGitHubRepositoryPathSegment(path);
		}
		URI uri = URI.create(normalizedUrl);
		if (!"github.com".equalsIgnoreCase(uri.getHost())) {
			throw new RepositoryScanException("Pull request checks currently require a github.com repository URL.");
		}
		return parseGitHubRepositoryPathSegment(uri.getPath());
	}

	private GitHubRepositoryPath parseGitHubRepositoryPathSegment(String rawPath) {
		String path = rawPath;
		if (path.startsWith("/")) {
			path = path.substring(1);
		}
		if (path.endsWith(".git")) {
			path = path.substring(0, path.length() - 4);
		}
		String[] parts = path.split("/");
		if (parts.length < 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
			throw new RepositoryScanException("Unable to derive GitHub owner and repository name from URL.");
		}
		return new GitHubRepositoryPath(parts[0], parts[1]);
	}

	private String textOrNull(JsonNode node) {
		return node.isMissingNode() || node.isNull() ? null : node.asText();
	}

	private OffsetDateTime parseOffsetDateTime(String value) {
		return StringUtils.hasText(value) ? OffsetDateTime.parse(value) : null;
	}

	private record RepositoryPullRequestTarget(
			long repositoryId,
			String url,
			String defaultBranch) {
	}

	private record GitHubRepositoryPath(String owner, String name) {
	}
}
