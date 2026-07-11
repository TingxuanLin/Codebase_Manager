package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.PullRequestRiskFindingResponse;
import com.codebasemanager.repositoryscan.dto.PullRequestRiskResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PullRequestRiskService {

	private static final String ANALYZER_VERSION = "rules-v1";

	private final JdbcTemplate jdbcTemplate;

	public PullRequestRiskService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Calculates, stores, and returns deterministic risk findings for a stored pull request diff.
	 */
	@Transactional
	public PullRequestRiskResponse analyzePullRequestRisk(long repositoryId, int pullRequestNumber) {
		ensureRepositoryExists(repositoryId);
		StoredPullRequest pullRequest = findStoredPullRequest(repositoryId, pullRequestNumber);
		List<PullRequestFileChange> changes = findPullRequestFileChanges(pullRequest.id());
		if (changes.isEmpty() && pullRequest.diffFetchedAt() == null) {
			throw new RepositoryScanException("No stored pull request diff found for repository pull request: " + pullRequestNumber);
		}

		List<PullRequestRiskFindingResponse> findings = new ArrayList<>();
		addVolumeFindings(changes, findings);
		addChurnFindings(changes, findings);
		addDeletedFileFindings(changes, findings);
		addDependencyManifestFindings(changes, findings);
		addSensitivePathFindings(changes, findings);
		addApiRouteFindings(pullRequest, changes, findings);
		addLargeFileFindings(pullRequest, changes, findings);
		addDependencyFanoutFindings(pullRequest, changes, findings);

		findings.sort(Comparator
				.comparingInt(PullRequestRiskFindingResponse::points).reversed()
				.thenComparing(finding -> finding.path() == null ? "" : finding.path())
				.thenComparing(PullRequestRiskFindingResponse::category));
		int score = Math.min(100, findings.stream().mapToInt(PullRequestRiskFindingResponse::points).sum());
		String severity = severity(score);
		OffsetDateTime generatedAt = storeAnalysis(pullRequest.id(), score, severity, findings);
		int additions = changes.stream().mapToInt(PullRequestFileChange::additions).sum();
		int deletions = changes.stream().mapToInt(PullRequestFileChange::deletions).sum();

		return new PullRequestRiskResponse(
				repositoryId,
				pullRequest.number(),
				pullRequest.title(),
				pullRequest.state(),
				pullRequest.baseBranch(),
				pullRequest.headBranch(),
				pullRequest.baseSha(),
				pullRequest.headSha(),
				score,
				severity,
				generatedAt,
				changes.size(),
				additions,
				deletions,
				findings);
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

	private StoredPullRequest findStoredPullRequest(long repositoryId, int number) {
		try {
			return jdbcTemplate.queryForObject("""
					SELECT repository_id, id, github_pr_number, title, state, base_branch, head_branch,
					       base_sha, head_sha, diff_fetched_at
					FROM pull_requests
					WHERE repository_id = ?
					  AND github_pr_number = ?
					""", (rs, rowNum) -> new StoredPullRequest(
					rs.getLong("repository_id"),
					rs.getLong("id"),
					rs.getInt("github_pr_number"),
					rs.getString("title"),
					rs.getString("state"),
					rs.getString("base_branch"),
					rs.getString("head_branch"),
					rs.getString("base_sha"),
					rs.getString("head_sha"),
					rs.getObject("diff_fetched_at", OffsetDateTime.class)), repositoryId, number);
		}
		catch (EmptyResultDataAccessException ex) {
			throw new RepositoryResourceNotFoundException("Pull request not found for repository: " + number);
		}
	}

	private List<PullRequestFileChange> findPullRequestFileChanges(long pullRequestId) {
		return jdbcTemplate.query("""
				SELECT path, old_path, change_type, additions, deletions, changes
				FROM pull_request_file_changes
				WHERE pull_request_id = ?
				ORDER BY path
				""", (rs, rowNum) -> new PullRequestFileChange(
				rs.getString("path"),
				rs.getString("old_path"),
				rs.getString("change_type"),
				rs.getInt("additions"),
				rs.getInt("deletions"),
				rs.getInt("changes")), pullRequestId);
	}

	private void addVolumeFindings(List<PullRequestFileChange> changes, List<PullRequestRiskFindingResponse> findings) {
		int changedFileCount = changes.size();
		if (changedFileCount > 20) {
			findings.add(finding("change_volume", "high", 20, null,
					"Pull request changes more than 20 files.",
					"changed_files=" + changedFileCount));
		}
		else if (changedFileCount > 10) {
			findings.add(finding("change_volume", "medium", 10, null,
					"Pull request changes more than 10 files.",
					"changed_files=" + changedFileCount));
		}
		else if (changedFileCount > 5) {
			findings.add(finding("change_volume", "low", 5, null,
					"Pull request changes more than 5 files.",
					"changed_files=" + changedFileCount));
		}
	}

	private void addChurnFindings(List<PullRequestFileChange> changes, List<PullRequestRiskFindingResponse> findings) {
		int additions = changes.stream().mapToInt(PullRequestFileChange::additions).sum();
		int deletions = changes.stream().mapToInt(PullRequestFileChange::deletions).sum();
		int churn = additions + deletions;
		if (churn > 1000) {
			findings.add(finding("code_churn", "high", 25, null,
					"Pull request has very high code churn.",
					"additions=" + additions + ", deletions=" + deletions));
		}
		else if (churn > 500) {
			findings.add(finding("code_churn", "medium", 15, null,
					"Pull request has high code churn.",
					"additions=" + additions + ", deletions=" + deletions));
		}
		else if (churn > 200) {
			findings.add(finding("code_churn", "low", 8, null,
					"Pull request has moderate code churn.",
					"additions=" + additions + ", deletions=" + deletions));
		}
	}

	private void addDeletedFileFindings(List<PullRequestFileChange> changes, List<PullRequestRiskFindingResponse> findings) {
		long deletedFiles = changes.stream()
				.filter(change -> "removed".equals(change.changeType()) || "deleted".equals(change.changeType()))
				.count();
		if (deletedFiles > 0) {
			findings.add(finding("deleted_files", "medium", 10, null,
					"Pull request deletes files.",
					"deleted_files=" + deletedFiles));
		}
	}

	private void addDependencyManifestFindings(List<PullRequestFileChange> changes, List<PullRequestRiskFindingResponse> findings) {
		for (PullRequestFileChange change : changes) {
			String fileName = fileName(change.path()).toLowerCase(Locale.ROOT);
			if (List.of("package.json", "package-lock.json", "pom.xml", "build.gradle", "build.gradle.kts",
					"requirements.txt", "pyproject.toml", "go.mod", "cargo.toml", "composer.json", "gemfile")
					.contains(fileName)) {
				findings.add(finding("dependency_change", "high", 20, change.path(),
						"Pull request changes a dependency manifest.",
						"file=" + change.path()));
			}
		}
	}

	private void addSensitivePathFindings(List<PullRequestFileChange> changes, List<PullRequestRiskFindingResponse> findings) {
		for (PullRequestFileChange change : changes) {
			String path = change.path().toLowerCase(Locale.ROOT);
			String category = sensitiveCategory(path);
			if (category != null) {
				findings.add(finding("sensitive_path", "medium", 15, change.path(),
						"Pull request touches " + category + " related code.",
						"category=" + category));
			}
		}
	}

	private void addApiRouteFindings(
			StoredPullRequest pullRequest,
			List<PullRequestFileChange> changes,
			List<PullRequestRiskFindingResponse> findings) {
		for (String path : changedPaths(changes)) {
			Integer routeCount = jdbcTemplate.queryForObject("""
					SELECT COUNT(*)::int
					FROM api_routes ar
					JOIN source_files sf ON sf.id = ar.file_id
					JOIN branches b ON b.id = sf.branch_id
					WHERE sf.repository_id = ?
					  AND b.repository_id = ?
					  AND b.name = ?
					  AND sf.path = ?
					""", Integer.class, pullRequest.repositoryId(), pullRequest.repositoryId(), pullRequest.headBranch(), path);
			if (routeCount != null && routeCount > 0) {
				findings.add(finding("api_surface", "high", 20, path,
						"Pull request touches a file with API routes.",
						"api_routes=" + routeCount));
			}
		}
	}

	private void addLargeFileFindings(
			StoredPullRequest pullRequest,
			List<PullRequestFileChange> changes,
			List<PullRequestRiskFindingResponse> findings) {
		for (String path : changedPaths(changes)) {
			FileComplexity fileComplexity = findFileComplexity(pullRequest, path);
			if (fileComplexity == null) {
				continue;
			}
			if (fileComplexity.loc() > 800 || fileComplexity.methodCount() > 40) {
				findings.add(finding("large_file", "medium", 12, path,
						"Pull request touches a large or method-heavy file.",
						"loc=" + fileComplexity.loc() + ", methods=" + fileComplexity.methodCount()));
			}
			else if (fileComplexity.loc() > 400 || fileComplexity.methodCount() > 20) {
				findings.add(finding("large_file", "low", 6, path,
						"Pull request touches a moderately large file.",
						"loc=" + fileComplexity.loc() + ", methods=" + fileComplexity.methodCount()));
			}
		}
	}

	private void addDependencyFanoutFindings(
			StoredPullRequest pullRequest,
			List<PullRequestFileChange> changes,
			List<PullRequestRiskFindingResponse> findings) {
		for (String path : changedPaths(changes)) {
			Integer dependencyCount = jdbcTemplate.queryForObject("""
					SELECT COUNT(d.id)::int
					FROM source_files sf
					JOIN branches b ON b.id = sf.branch_id
					JOIN classes c ON c.file_id = sf.id
					LEFT JOIN dependencies d ON d.source_class = c.id OR d.target_class = c.id
					WHERE sf.repository_id = ?
					  AND b.repository_id = ?
					  AND b.name = ?
					  AND sf.path = ?
					""", Integer.class, pullRequest.repositoryId(), pullRequest.repositoryId(), pullRequest.headBranch(), path);
			if (dependencyCount != null && dependencyCount > 15) {
				findings.add(finding("dependency_fanout", "medium", 10, path,
						"Pull request touches a file with many internal dependency edges.",
						"dependency_edges=" + dependencyCount));
			}
		}
	}

	private FileComplexity findFileComplexity(StoredPullRequest pullRequest, String path) {
		return jdbcTemplate.query("""
				SELECT sf.loc, COALESCE(SUM(c.method_count), 0)::int AS method_count
				FROM source_files sf
				JOIN branches b ON b.id = sf.branch_id
				LEFT JOIN classes c ON c.file_id = sf.id
				WHERE sf.repository_id = ?
				  AND b.repository_id = ?
				  AND b.name = ?
				  AND sf.path = ?
				GROUP BY sf.id, sf.loc
				""", rs -> {
			if (!rs.next()) {
				return null;
			}
			return new FileComplexity(rs.getInt("loc"), rs.getInt("method_count"));
		}, pullRequest.repositoryId(), pullRequest.repositoryId(), pullRequest.headBranch(), path);
	}

	private OffsetDateTime storeAnalysis(
			long pullRequestId,
			int score,
			String severity,
			List<PullRequestRiskFindingResponse> findings) {
		Long analysisId = jdbcTemplate.queryForObject("""
				INSERT INTO pull_request_risk_analyses (pull_request_id, score, severity, analyzer_version, generated_at)
				VALUES (?, ?, ?, ?, NOW())
				ON CONFLICT (pull_request_id) DO UPDATE
				SET score = EXCLUDED.score,
				    severity = EXCLUDED.severity,
				    analyzer_version = EXCLUDED.analyzer_version,
				    generated_at = NOW()
				RETURNING id
				""", Long.class, pullRequestId, score, severity, ANALYZER_VERSION);
		if (analysisId == null) {
			throw new RepositoryScanException("Database did not return a risk analysis id.");
		}
		jdbcTemplate.update("DELETE FROM pull_request_risk_findings WHERE analysis_id = ?", analysisId);
		for (PullRequestRiskFindingResponse finding : findings) {
			jdbcTemplate.update("""
					INSERT INTO pull_request_risk_findings
					    (analysis_id, category, severity, points, path, reason, evidence)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					""", analysisId, finding.category(), finding.severity(), finding.points(),
					finding.path(), finding.reason(), finding.evidence());
		}
		return jdbcTemplate.queryForObject(
				"SELECT generated_at FROM pull_request_risk_analyses WHERE id = ?",
				OffsetDateTime.class,
				analysisId);
	}

	private List<String> changedPaths(List<PullRequestFileChange> changes) {
		return changes.stream()
				.map(PullRequestFileChange::path)
				.filter(StringUtils::hasText)
				.distinct()
				.toList();
	}

	private PullRequestRiskFindingResponse finding(
			String category,
			String severity,
			int points,
			String path,
			String reason,
			String evidence) {
		return new PullRequestRiskFindingResponse(category, severity, points, path, reason, evidence);
	}

	private String fileName(String path) {
		int slashIndex = path.lastIndexOf('/');
		return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
	}

	private String sensitiveCategory(String path) {
		if (path.contains("auth") || path.contains("login") || path.contains("permission") || path.contains("security")) {
			return "auth/security";
		}
		if (path.contains("payment") || path.contains("billing") || path.contains("invoice") || path.contains("checkout")) {
			return "payment/billing";
		}
		if (path.contains("migration") || path.contains("database") || path.contains("/db/") || path.contains("schema")) {
			return "database/schema";
		}
		if (path.contains("config") || path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties") || path.endsWith(".env")) {
			return "configuration";
		}
		return null;
	}

	private String severity(int score) {
		if (score >= 80) {
			return "critical";
		}
		if (score >= 60) {
			return "high";
		}
		if (score >= 30) {
			return "medium";
		}
		return "low";
	}

	private record StoredPullRequest(
			long repositoryId,
			long id,
			int number,
			String title,
			String state,
			String baseBranch,
			String headBranch,
			String baseSha,
			String headSha,
			OffsetDateTime diffFetchedAt) {
	}

	private record PullRequestFileChange(
			String path,
			String oldPath,
			String changeType,
			int additions,
			int deletions,
			int changes) {
	}

	private record FileComplexity(int loc, int methodCount) {
	}
}
