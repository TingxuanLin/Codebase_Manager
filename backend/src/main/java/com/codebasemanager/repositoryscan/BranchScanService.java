package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.BranchComparisonResponse;
import com.codebasemanager.repositoryscan.dto.BranchSummaryResponse;
import com.codebasemanager.repositoryscan.dto.FileChangeResponse;
import com.codebasemanager.repositoryscan.dto.GitHubBranchResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class BranchScanService {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * Receives the JDBC helper used for branch scan persistence and queries.
	 */
	public BranchScanService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Lists all branches available on a remote GitHub repository without cloning it.
	 */
	public List<GitHubBranchResponse> listGitHubBranches(String url) {
		String output = runGitCommand(Path.of("."), List.of("ls-remote", "--heads", url));
		if (!StringUtils.hasText(output)) {
			return List.of();
		}
		return output.lines()
				.map(String::strip)
				.filter(StringUtils::hasText)
				.map(this::parseGitBranchLine)
				.toList();
	}

	/**
	 * Checks out the requested branch, or the remote default branch when none is provided.
	 */
	void checkoutRequestedBranch(Path repoPath, String branch) {
		if (StringUtils.hasText(branch)) {
			String requestedBranch = branch.strip();
			if (branchExists(repoPath, requestedBranch)) {
				runGit(repoPath, "checkout", requestedBranch);
			}
			else {
				runGit(repoPath, "checkout", "-B", requestedBranch, "origin/" + requestedBranch);
			}
			runGit(repoPath, "pull", "--ff-only");
			return;
		}

		String defaultBranch = remoteDefaultBranch(repoPath);
		runGit(repoPath, "checkout", "-B", defaultBranch, "origin/" + defaultBranch);
		runGit(repoPath, "pull", "--ff-only");
	}

	/**
	 * Lists all stored branches for a repository, most recently updated first.
	 */
	@Transactional(readOnly = true)
	public List<BranchSummaryResponse> listBranches(long repositoryId) {
		if (!repositoryExists(repositoryId)) {
			throw new RepositoryResourceNotFoundException("Repository not found: " + repositoryId);
		}

		return jdbcTemplate.query("""
				SELECT id, name, is_default, last_scanned_commit_sha
				FROM branches
				WHERE repository_id = ?
				ORDER BY is_default DESC, updated_at DESC
				""",
				(resultSet, rowNumber) -> new BranchSummaryResponse(
						resultSet.getLong("id"),
						resultSet.getString("name"),
						resultSet.getBoolean("is_default"),
						resultSet.getString("last_scanned_commit_sha")),
				repositoryId);
	}

	/**
	 * Deletes one branch and branch-scoped records for a repository.
	 */
	@Transactional
	public void deleteBranch(long repositoryId, long branchId) {
		validateRepositoryAndBranch(repositoryId, branchId);

		jdbcTemplate.update("""
				UPDATE repositories
				SET default_branch_id = NULL, updated_at = NOW()
				WHERE id = ? AND default_branch_id = ?
				""", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM source_files WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM repository_metrics WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM analysis_artifacts WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM risk_scores WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update(
				"DELETE FROM branches WHERE repository_id = ? AND id = ?",
				repositoryId,
				branchId);
	}

	/**
	 * Makes one branch the default branch used as the comparison base.
	 */
	@Transactional
	public void setDefaultBranch(long repositoryId, long branchId) {
		validateRepositoryAndBranch(repositoryId, branchId);

		jdbcTemplate.update("UPDATE branches SET is_default = FALSE, updated_at = NOW() WHERE repository_id = ?", repositoryId);
		jdbcTemplate.update("""
				UPDATE repositories
				SET default_branch_id = ?, updated_at = NOW()
				WHERE id = ?
				""", branchId, repositoryId);
		syncDefaultBranchFlag(repositoryId);
	}

	/**
	 * Returns the latest branch scan and its stored file changes against the default branch.
	 */
	@Transactional(readOnly = true)
	public BranchComparisonResponse getBranchComparison(long repositoryId, long branchId) {
		validateRepositoryAndBranch(repositoryId, branchId);

		BranchComparisonMetadata metadata = jdbcTemplate.query("""
				SELECT sr.id AS scan_run_id,
				       current_branch.name AS branch,
				       default_branch.name AS default_branch,
				       sr.base_commit_sha,
				       sr.head_commit_sha,
				       sr.completed_at AS scanned_at
				FROM scan_runs sr
				JOIN branches current_branch ON current_branch.repository_id = sr.repository_id
				    AND current_branch.id = sr.branch_id
				JOIN repositories repository ON repository.id = sr.repository_id
				LEFT JOIN branches default_branch ON default_branch.repository_id = repository.id
				    AND default_branch.id = repository.default_branch_id
				WHERE sr.repository_id = ?
				  AND sr.branch_id = ?
				  AND sr.status = 'completed'
				ORDER BY COALESCE(sr.completed_at, sr.started_at) DESC, sr.id DESC
				LIMIT 1
				""", rs -> {
			if (!rs.next()) {
				throw new RepositoryScanException("No completed scan found for branch: " + branchId);
			}
			return new BranchComparisonMetadata(
					rs.getLong("scan_run_id"),
					rs.getString("branch"),
					rs.getString("default_branch"),
					rs.getString("base_commit_sha"),
					rs.getString("head_commit_sha"),
					rs.getObject("scanned_at", OffsetDateTime.class));
		}, repositoryId, branchId);

		List<FileChangeResponse> changes = jdbcTemplate.query("""
				SELECT path, old_path, change_type, additions, deletions
				FROM file_changes
				WHERE scan_run_id = ?
				ORDER BY path ASC
				""", (rs, rowNum) -> new FileChangeResponse(
				rs.getString("path"),
				rs.getString("old_path"),
				rs.getString("change_type"),
				rs.getInt("additions"),
				rs.getInt("deletions")), metadata.scanRunId());

		int additions = changes.stream().mapToInt(FileChangeResponse::additions).sum();
		int deletions = changes.stream().mapToInt(FileChangeResponse::deletions).sum();

		return new BranchComparisonResponse(
				repositoryId,
				branchId,
				metadata.scanRunId(),
				metadata.branch(),
				metadata.defaultBranch(),
				metadata.baseCommitSha(),
				metadata.headCommitSha(),
				metadata.scannedAt(),
				changes.size(),
				additions,
				deletions,
				changes);
	}

	/**
	 * Finds the configured default branch commit when scanning a non-default branch.
	 */
	Optional<String> findComparisonBaseCommitSha(long repositoryId, long branchId) {
		return jdbcTemplate.query("""
				SELECT default_branch.last_scanned_commit_sha
				FROM branches current_branch
				JOIN repositories repository ON repository.id = current_branch.repository_id
				JOIN branches default_branch ON default_branch.repository_id = repository.id
				    AND default_branch.id = repository.default_branch_id
				WHERE current_branch.repository_id = ?
				  AND current_branch.id = ?
				  AND current_branch.id <> repository.default_branch_id
				  AND default_branch.last_scanned_commit_sha IS NOT NULL
				""", rs -> rs.next() ? Optional.of(rs.getString("last_scanned_commit_sha")) : Optional.empty(), repositoryId, branchId);
	}

	/**
	 * Stores file-level changes for a branch scan compared to the default branch commit.
	 */
	void insertFileChanges(Path repoPath, long scanRunId, String baseCommitSha, String headCommitSha) {
		if (!StringUtils.hasText(baseCommitSha) || baseCommitSha.equals(headCommitSha)) {
			return;
		}
		if (!gitObjectExists(repoPath, baseCommitSha) || !gitObjectExists(repoPath, headCommitSha)) {
			return;
		}

		Map<String, FileChangeCounts> countsByPath = diffCountsByPath(repoPath, baseCommitSha, headCommitSha);
		for (FileChange change : diffNameStatuses(repoPath, baseCommitSha, headCommitSha)) {
			FileChangeCounts counts = countsByPath.getOrDefault(change.path(), new FileChangeCounts(0, 0));
			jdbcTemplate.update("""
					INSERT INTO file_changes (scan_run_id, path, old_path, change_type, additions, deletions)
					VALUES (?, ?, ?, ?, ?, ?)
					ON CONFLICT (scan_run_id, path) DO UPDATE
					SET old_path = EXCLUDED.old_path,
					    change_type = EXCLUDED.change_type,
					    additions = EXCLUDED.additions,
					    deletions = EXCLUDED.deletions
					""", scanRunId, change.path(), change.oldPath(), change.changeType(), counts.additions(), counts.deletions());
		}
	}

	/**
	 * Returns whether the requested repository row exists.
	 */
	private boolean repositoryExists(long repositoryId) {
		Integer repositoryCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM repositories WHERE id = ?",
				Integer.class,
				repositoryId);
		return repositoryCount != null && repositoryCount > 0;
	}

	/**
	 * Returns whether the repository contains the requested branch row.
	 */
	private boolean branchExists(long repositoryId, long branchId) {
		Integer branchCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM branches WHERE repository_id = ? AND id = ?",
				Integer.class,
				repositoryId,
				branchId);
		return branchCount != null && branchCount > 0;
	}

	/**
	 * Validates that the repository exists and owns the requested branch.
	 */
	private void validateRepositoryAndBranch(long repositoryId, long branchId) {
		if (!repositoryExists(repositoryId)) {
			throw new RepositoryResourceNotFoundException("Repository not found: " + repositoryId);
		}
		if (!branchExists(repositoryId, branchId)) {
			throw new RepositoryResourceNotFoundException("Branch not found for repository: " + branchId);
		}
	}

	/**
	 * Keeps the branch-level default marker aligned with repositories.default_branch_id.
	 */
	private void syncDefaultBranchFlag(long repositoryId) {
		jdbcTemplate.update("""
				UPDATE branches b
				SET is_default = (b.id = r.default_branch_id),
				    updated_at = CASE
				        WHEN b.is_default IS DISTINCT FROM (b.id = r.default_branch_id) THEN NOW()
				        ELSE b.updated_at
				    END
				FROM repositories r
				WHERE b.repository_id = r.id
				  AND r.id = ?
				""", repositoryId);
	}

	/**
	 * Parses one git ls-remote branch line into a branch response.
	 */
	private GitHubBranchResponse parseGitBranchLine(String line) {
		String[] parts = line.split("\\s+", 2);
		if (parts.length != 2 || !parts[1].startsWith("refs/heads/")) {
			throw new RepositoryScanException("Unexpected git branch output: " + line);
		}
		return new GitHubBranchResponse(parts[1].substring("refs/heads/".length()), parts[0]);
	}

	/**
	 * Returns whether a local branch name already exists.
	 */
	private boolean branchExists(Path repoPath, String branch) {
		try {
			runGit(repoPath, "rev-parse", "--verify", branch);
			return true;
		}
		catch (RepositoryScanException ex) {
			return false;
		}
	}

	/**
	 * Reads origin/HEAD to find the remote default branch, falling back to main.
	 */
	private String remoteDefaultBranch(Path repoPath) {
		try {
			String originHead = runGit(repoPath, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
			if (originHead.startsWith("origin/")) {
				return originHead.substring("origin/".length());
			}
		}
		catch (RepositoryScanException ex) {
			runGit(repoPath, "remote", "set-head", "origin", "--auto");
			String originHead = runGit(repoPath, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
			if (originHead.startsWith("origin/")) {
				return originHead.substring("origin/".length());
			}
		}
		return "main";
	}

	/**
	 * Returns whether a commit or tree-ish can be resolved locally.
	 */
	private boolean gitObjectExists(Path repoPath, String objectName) {
		try {
			runGit(repoPath, "cat-file", "-e", objectName);
			return true;
		}
		catch (RepositoryScanException ex) {
			return false;
		}
	}

	/**
	 * Parses git numstat output into addition/deletion counts keyed by the new path.
	 */
	private Map<String, FileChangeCounts> diffCountsByPath(Path repoPath, String baseCommitSha, String headCommitSha) {
		String output = runGit(repoPath, "diff", "--numstat", "--find-renames", "--find-copies", baseCommitSha, headCommitSha);
		Map<String, FileChangeCounts> countsByPath = new HashMap<>();
		if (!StringUtils.hasText(output)) {
			return countsByPath;
		}
		for (String line : output.lines().toList()) {
			String[] parts = line.split("\\t");
			if (parts.length < 3) {
				continue;
			}
			String path = parts[parts.length - 1];
			countsByPath.put(path, new FileChangeCounts(parseDiffCount(parts[0]), parseDiffCount(parts[1])));
		}
		return countsByPath;
	}

	/**
	 * Parses git name-status output into file change records.
	 */
	private List<FileChange> diffNameStatuses(Path repoPath, String baseCommitSha, String headCommitSha) {
		String output = runGit(repoPath, "diff", "--name-status", "--find-renames", "--find-copies", baseCommitSha, headCommitSha);
		if (!StringUtils.hasText(output)) {
			return List.of();
		}
		List<FileChange> changes = new ArrayList<>();
		for (String line : output.lines().toList()) {
			String[] parts = line.split("\\t");
			if (parts.length < 2) {
				continue;
			}
			String status = parts[0];
			String changeType = changeType(status);
			if (status.startsWith("R") || status.startsWith("C")) {
				if (parts.length >= 3) {
					changes.add(new FileChange(parts[2], parts[1], changeType));
				}
			}
			else {
				changes.add(new FileChange(parts[1], null, changeType));
			}
		}
		return changes;
	}

	/**
	 * Converts git name-status codes into the database change_type values.
	 */
	private String changeType(String status) {
		if (status.startsWith("A")) {
			return "added";
		}
		if (status.startsWith("D")) {
			return "deleted";
		}
		if (status.startsWith("R")) {
			return "renamed";
		}
		if (status.startsWith("C")) {
			return "copied";
		}
		return "modified";
	}

	/**
	 * Parses numstat counts, treating binary file markers as zero.
	 */
	private int parseDiffCount(String value) {
		if ("-".equals(value)) {
			return 0;
		}
		return Integer.parseInt(value);
	}

	/**
	 * Runs a Git command inside a repository path.
	 */
	private String runGit(Path repoPath, String... args) {
		return runGitCommand(repoPath, List.of(args));
	}

	/**
	 * Runs a Git command in the provided working directory and returns stdout.
	 */
	private String runGitCommand(Path workingDirectory, List<String> args) {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(args);
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(workingDirectory.toFile());
		processBuilder.redirectErrorStream(true);
		try {
			Process process = processBuilder.start();
			String output;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				output = reader.lines().reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right).trim();
			}
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new RepositoryScanException("Git command failed: git " + String.join(" ", args) + "\n" + output);
			}
			return output;
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to run git. Ensure git is installed and available on PATH.", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RepositoryScanException("Git command was interrupted.", ex);
		}
	}

	private record BranchComparisonMetadata(
			long scanRunId,
			String branch,
			String defaultBranch,
			String baseCommitSha,
			String headCommitSha,
			OffsetDateTime scannedAt) {
	}

	private record FileChange(String path, String oldPath, String changeType) {
	}

	private record FileChangeCounts(int additions, int deletions) {
	}
}
