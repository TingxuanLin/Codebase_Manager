package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.ParseGitHubRepositoryRequest;
import com.codebasemanager.repositoryscan.dto.ParseRepositoryRequest;
import com.codebasemanager.repositoryscan.dto.ParseRepositoryResponse;
import com.codebasemanager.repositoryscan.dto.GitHubBranchResponse;
import com.codebasemanager.repositoryscan.dto.RepositorySummaryResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class RepositoryScanService {

	private static final long MAX_SOURCE_FILE_BYTES = 1_000_000L;
	private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
	private static final Pattern PYTHON_CLASS_PATTERN = Pattern.compile("^\\s*class\\s+([A-Za-z_][\\w]*)");
	private static final Pattern JAVA_LIKE_METHOD_PATTERN = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{?");
	private static final Pattern FUNCTION_PATTERN = Pattern.compile("\\bfunction\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
	private static final Pattern ARROW_FUNCTION_PATTERN = Pattern.compile("\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>");
	private static final Pattern PYTHON_METHOD_PATTERN = Pattern.compile("^\\s*def\\s+([A-Za-z_][\\w]*)\\s*\\(");
	private static final List<String> SKIPPED_DIRECTORIES = List.of(
			".git", ".gradle", ".idea", ".vscode", "build", "dist", "node_modules", "out", "target",
			"coverage", ".venv", "venv", "__pycache__");

	private final JdbcTemplate jdbcTemplate;

	/**
	 * Receives the JDBC helper used for all parse persistence operations.
	 */
	public RepositoryScanService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Lists every stored repository with its latest scan and metrics summary.
	 */
	@Transactional(readOnly = true)
	public List<RepositorySummaryResponse> listRepositories() {
		return jdbcTemplate.query("""
				SELECT r.id,
				       r.name,
				       r.url,
				       COUNT(DISTINCT b.id)::int AS branch_count,
				       latest_branch.name AS latest_branch,
				       latest_scan.head_commit_sha AS latest_commit_sha,
				       latest_scan.id AS latest_scan_run_id,
				       latest_scan.status AS latest_scan_status,
				       latest_scan.completed_at AS last_scanned_at,
				       COALESCE(latest_metrics.file_count, 0) AS file_count,
				       COALESCE(latest_metrics.class_count, 0) AS class_count,
				       COALESCE(latest_metrics.method_count, 0) AS method_count
				FROM repositories r
				LEFT JOIN branches b ON b.repository_id = r.id
				LEFT JOIN LATERAL (
				    SELECT sr.*
				    FROM scan_runs sr
				    WHERE sr.repository_id = r.id
				    ORDER BY COALESCE(sr.completed_at, sr.started_at) DESC, sr.id DESC
				    LIMIT 1
				) latest_scan ON TRUE
				LEFT JOIN branches latest_branch ON latest_branch.id = latest_scan.branch_id
				LEFT JOIN LATERAL (
				    SELECT rm.file_count, rm.class_count, rm.method_count
				    FROM repository_metrics rm
				    WHERE rm.repository_id = r.id
				      AND rm.branch_id IS NOT DISTINCT FROM latest_scan.branch_id
				    ORDER BY rm.date DESC, rm.id DESC
				    LIMIT 1
				) latest_metrics ON TRUE
				GROUP BY r.id, latest_branch.name, latest_scan.id, latest_scan.head_commit_sha,
				         latest_scan.status, latest_scan.completed_at, latest_scan.started_at,
				         latest_metrics.file_count, latest_metrics.class_count, latest_metrics.method_count
				ORDER BY COALESCE(latest_scan.completed_at, latest_scan.started_at, r.updated_at) DESC, r.name ASC
				""", (rs, rowNum) -> new RepositorySummaryResponse(
				rs.getLong("id"),
				rs.getString("name"),
				rs.getString("url"),
				rs.getInt("branch_count"),
				rs.getString("latest_branch"),
				rs.getString("latest_commit_sha"),
				getNullableLong(rs, "latest_scan_run_id"),
				rs.getString("latest_scan_status"),
				rs.getObject("last_scanned_at", OffsetDateTime.class),
				rs.getInt("file_count"),
				rs.getInt("class_count"),
				rs.getInt("method_count")));
	}

	/**
	 * Lists all branches available on a remote GitHub repository without cloning it.
	 */
	public List<GitHubBranchResponse> listGitHubBranches(String url) {
		validateGitRepositoryUrl(url);
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
	 * Validates a local Git working tree and stores its parsed repository snapshot.
	 */
	@Transactional
	public ParseRepositoryResponse parseAndStore(ParseRepositoryRequest request) {
		Path repoPath = resolveRepositoryPath(request.path());
		String repositoryName = firstNonBlank(request.name(), repoPath.getFileName().toString());
		String repositoryUrl = firstNonBlank(request.url(), findRemoteUrl(repoPath).orElse(toFileUri(repoPath)));
		return parseAndStore(repoPath, repositoryName, repositoryUrl);
	}

	/**
	 * Clones or updates a GitHub repository and stores the parsed snapshot.
	 */
	@Transactional
	public ParseRepositoryResponse parseGitHubAndStore(ParseGitHubRepositoryRequest request) {
		validateGitRepositoryUrl(request.url());
		Path repoPath = cloneOrUpdateRepository(request.url(), request.branch());
		String repositoryName = firstNonBlank(request.name(), deriveRepositoryName(request.url(), repoPath));
		return parseAndStore(new ParseRepositoryRequest(repoPath.toString(), repositoryName, request.url()));
	}

	/**
	 * Deletes a repository row and relies on database cascades for dependent records.
	 */
	@Transactional
	public void deleteRepository(long repositoryId) {
		int deletedRows = jdbcTemplate.update("DELETE FROM repositories WHERE id = ?", repositoryId);
		if (deletedRows == 0) {
			throw new RepositoryScanException("Repository not found: " + repositoryId);
		}
	}

	/**
	 * Deletes one branch and branch-scoped records for a repository.
	 */
	@Transactional
	public void deleteBranch(long repositoryId, long branchId) {
		jdbcTemplate.update("DELETE FROM source_files WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM repository_metrics WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM analysis_artifacts WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM risk_scores WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		int deletedRows = jdbcTemplate.update(
				"DELETE FROM branches WHERE repository_id = ? AND id = ?",
				repositoryId,
				branchId);
		if (deletedRows == 0) {
			throw new RepositoryScanException("Branch not found for repository: " + branchId);
		}
	}

	/**
	 * Persists Git metadata, parsed source files, classes, methods, and metrics.
	 */
	@Transactional
	protected ParseRepositoryResponse parseAndStore(Path repoPath, String repositoryName, String repositoryUrl) {
		GitInfo gitInfo = readGitInfo(repoPath);

		List<ParsedSourceFile> files = parseSourceFiles(repoPath);
		long repositoryId = upsertRepository(repositoryName, repositoryUrl);
		long commitId = upsertCommit(repositoryId, gitInfo);
		long branchId = upsertBranch(repositoryId, gitInfo);
		linkBranchCommit(repositoryId, branchId, commitId);
		long scanRunId = insertScanRun(repositoryId, branchId, gitInfo.headCommitSha());

		jdbcTemplate.update("DELETE FROM source_files WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);

		int classCount = 0;
		int methodCount = 0;
		for (ParsedSourceFile file : files) {
			long fileId = insertSourceFile(repositoryId, branchId, scanRunId, file);
			classCount += file.classes().size();
			for (ParsedClass parsedClass : file.classes()) {
				long classId = insertClass(fileId, parsedClass);
				methodCount += parsedClass.methods().size();
				for (ParsedMethod method : parsedClass.methods()) {
					insertMethod(classId, method);
				}
			}
		}

		upsertRepositoryMetrics(repositoryId, branchId, scanRunId, files.size(), classCount, methodCount);
		completeScanRun(scanRunId);

		return new ParseRepositoryResponse(
				repositoryId,
				branchId,
				scanRunId,
				repositoryName,
				gitInfo.branchName(),
				gitInfo.headCommitSha(),
				files.size(),
				classCount,
				methodCount);
	}

	/**
	 * Ensures a GitHub repository exists in the local cache and is up to date.
	 */
	private Path cloneOrUpdateRepository(String url, String branch) {
		Path cachePath = repositoryCachePath(url);
		try {
			Files.createDirectories(cachePath.getParent());
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to create repository cache directory.", ex);
		}

		if (Files.isDirectory(cachePath.resolve(".git"))) {
			runGit(cachePath, "remote", "set-url", "origin", url);
			runGit(cachePath, "fetch", "--all", "--prune");
			checkoutRequestedBranch(cachePath, branch);
			return cachePath;
		}

		List<String> cloneArgs = new ArrayList<>(List.of("clone", url, cachePath.toString()));
		runGitCommand(Path.of("."), cloneArgs);
		checkoutRequestedBranch(cachePath, branch);
		return cachePath;
	}

	/**
	 * Checks out the requested branch, or the remote default branch when none is provided.
	 */
	private void checkoutRequestedBranch(Path repoPath, String branch) {
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
	 * Returns whether a local branch name already exists in the cached repository.
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
	 * Converts user input into an absolute path and verifies it is a Git working tree.
	 */
	private Path resolveRepositoryPath(String rawPath) {
		Path path = Path.of(rawPath).toAbsolutePath().normalize();
		if (!Files.isDirectory(path)) {
			throw new RepositoryScanException("Repository path must be an existing directory: " + rawPath);
		}
		if (!Files.isDirectory(path.resolve(".git"))) {
			throw new RepositoryScanException("Repository path must be a Git working tree: " + rawPath);
		}
		return path;
	}

	/**
	 * Reads current branch, head commit, and latest commit metadata from Git.
	 */
	private GitInfo readGitInfo(Path repoPath) {
		String branch = runGit(repoPath, "rev-parse", "--abbrev-ref", "HEAD");
		String headSha = runGit(repoPath, "rev-parse", "HEAD");
		String authorName = runGit(repoPath, "show", "-s", "--format=%an", "HEAD");
		String authorEmail = runGit(repoPath, "show", "-s", "--format=%ae", "HEAD");
		String message = runGit(repoPath, "show", "-s", "--format=%B", "HEAD");
		String committedAt = runGit(repoPath, "show", "-s", "--format=%cI", "HEAD");
		return new GitInfo(branch, headSha, authorName, authorEmail, message, committedAt);
	}

	/**
	 * Returns remote.origin.url when the repository has an origin configured.
	 */
	private Optional<String> findRemoteUrl(Path repoPath) {
		try {
			String remoteUrl = runGit(repoPath, "config", "--get", "remote.origin.url");
			return StringUtils.hasText(remoteUrl) ? Optional.of(remoteUrl) : Optional.empty();
		}
		catch (RepositoryScanException ex) {
			return Optional.empty();
		}
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
	 * Rejects empty or unsupported repository URL formats before running Git.
	 */
	private void validateGitRepositoryUrl(String url) {
		if (!StringUtils.hasText(url)) {
			throw new RepositoryScanException("Repository URL is required.");
		}
		String strippedUrl = url.strip();
		if (strippedUrl.startsWith("git@")) {
			return;
		}
		try {
			URI uri = new URI(strippedUrl);
			if (!List.of("https", "http", "ssh").contains(uri.getScheme())) {
				throw new RepositoryScanException("Repository URL must use https, http, ssh, or git@ syntax.");
			}
			if (!StringUtils.hasText(uri.getHost())) {
				throw new RepositoryScanException("Repository URL must include a host.");
			}
		}
		catch (URISyntaxException ex) {
			throw new RepositoryScanException("Repository URL is invalid: " + url, ex);
		}
	}

	/**
	 * Builds a stable local cache path for a remote repository URL.
	 */
	private Path repositoryCachePath(String url) {
		String cacheRoot = System.getenv().getOrDefault(
				"CODEBASE_REPOSITORY_CACHE_DIR",
				System.getProperty("user.home") + "/.codebase-manager/repositories");
		String repoName = sanitizePathSegment(deriveRepositoryName(url, Path.of("repository")));
		return Path.of(cacheRoot).resolve(repoName + "-" + sha256(url).substring(0, 12));
	}

	/**
	 * Derives a display name from a repository URL, such as org/project.git -> project.
	 */
	private String deriveRepositoryName(String url, Path fallbackPath) {
		String trimmedUrl = url.strip();
		int slashIndex = trimmedUrl.lastIndexOf('/');
		int colonIndex = trimmedUrl.lastIndexOf(':');
		int separatorIndex = Math.max(slashIndex, colonIndex);
		String candidate = separatorIndex >= 0 ? trimmedUrl.substring(separatorIndex + 1) : trimmedUrl;
		if (candidate.endsWith(".git")) {
			candidate = candidate.substring(0, candidate.length() - 4);
		}
		return StringUtils.hasText(candidate) ? candidate : fallbackPath.getFileName().toString();
	}

	/**
	 * Replaces unsafe path characters so derived names can be used as directory names.
	 */
	private String sanitizePathSegment(String value) {
		return value.replaceAll("[^A-Za-z0-9._-]", "-");
	}

	/**
	 * Hashes a URL so different repositories with the same folder name do not collide.
	 */
	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new RepositoryScanException("SHA-256 is not available.", ex);
		}
	}

	/**
	 * Walks supported source files and parses each one into a lightweight structure.
	 */
	private List<ParsedSourceFile> parseSourceFiles(Path repoPath) {
		try (var stream = Files.walk(repoPath)) {
			return stream
					.filter(Files::isRegularFile)
					.filter(path -> !isSkipped(repoPath, path))
					.filter(path -> detectLanguage(path).isPresent())
					.filter(this::isReadableSourceFile)
					.map(path -> parseSourceFile(repoPath, path))
					.toList();
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to walk repository files.", ex);
		}
	}

	/**
	 * Skips generated, dependency, build, and VCS directories during repository walks.
	 */
	private boolean isSkipped(Path repoPath, Path file) {
		Path relativePath = repoPath.relativize(file);
		for (Path part : relativePath) {
			if (SKIPPED_DIRECTORIES.contains(part.toString())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Avoids parsing very large source files that are likely generated or expensive.
	 */
	private boolean isReadableSourceFile(Path path) {
		try {
			return Files.size(path) <= MAX_SOURCE_FILE_BYTES;
		}
		catch (IOException ex) {
			return false;
		}
	}

	/**
	 * Reads one source file, detects language and LOC, and extracts classes/methods.
	 */
	private ParsedSourceFile parseSourceFile(Path repoPath, Path path) {
		String language = detectLanguage(path).orElse("Text");
		String relativePath = repoPath.relativize(path).toString().replace('\\', '/');
		List<String> lines;
		try {
			lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to read source file: " + relativePath, ex);
		}
		int loc = (int) lines.stream().filter(line -> StringUtils.hasText(line.strip())).count();
		List<ParsedClass> classes = parseClasses(language, path, lines, loc);
		return new ParsedSourceFile(relativePath, language, loc, classes);
	}

	/**
	 * Extracts class-like declarations and attaches detected methods to them.
	 */
	private List<ParsedClass> parseClasses(String language, Path path, List<String> lines, int fileLoc) {
		Map<String, ParsedClassBuilder> classes = new LinkedHashMap<>();
		for (String line : lines) {
			Matcher matcher = "Python".equals(language) ? PYTHON_CLASS_PATTERN.matcher(line) : CLASS_PATTERN.matcher(line);
			while (matcher.find()) {
				String name = uniqueClassName(classes, matcher.group(1));
				classes.put(name, new ParsedClassBuilder(name));
			}
		}

		List<String> methods = parseMethods(language, lines);
		if (classes.isEmpty() && !methods.isEmpty()) {
			String fallbackClass = fallbackClassName(path);
			ParsedClassBuilder builder = new ParsedClassBuilder(fallbackClass);
			methods.forEach(builder::addMethod);
			return List.of(builder.build(fileLoc));
		}

		if (!classes.isEmpty()) {
			ParsedClassBuilder firstClass = classes.values().iterator().next();
			methods.forEach(firstClass::addMethod);
		}
		return classes.values().stream().map(builder -> builder.build(fileLoc)).toList();
	}

	/**
	 * Extracts function or method names with language-specific regex patterns.
	 */
	private List<String> parseMethods(String language, List<String> lines) {
		Map<String, Integer> seen = new HashMap<>();
		List<String> methods = new ArrayList<>();
		for (String line : lines) {
			List<Pattern> patterns = methodPatterns(language);
			for (Pattern pattern : patterns) {
				Matcher matcher = pattern.matcher(line);
				while (matcher.find()) {
					String methodName = matcher.group(1);
					if (isControlKeyword(methodName)) {
						continue;
					}
					methods.add(uniqueMethodName(seen, methodName));
				}
			}
		}
		return methods;
	}

	/**
	 * Chooses the regex patterns used for method detection by language.
	 */
	private List<Pattern> methodPatterns(String language) {
		return switch (language) {
			case "Python" -> List.of(PYTHON_METHOD_PATTERN);
			case "JavaScript", "TypeScript" -> List.of(FUNCTION_PATTERN, ARROW_FUNCTION_PATTERN, JAVA_LIKE_METHOD_PATTERN);
			default -> List.of(JAVA_LIKE_METHOD_PATTERN);
		};
	}

	/**
	 * Filters control-flow keywords that can look like function calls in regex matches.
	 */
	private boolean isControlKeyword(String name) {
		return List.of("if", "for", "while", "switch", "catch", "return", "new", "throw", "else", "do").contains(name);
	}

	/**
	 * Creates a stable class name when a file contains duplicate class declarations.
	 */
	private String uniqueClassName(Map<String, ParsedClassBuilder> seen, String name) {
		if (!seen.containsKey(name)) {
			return name;
		}
		int suffix = 2;
		while (seen.containsKey(name + "#" + suffix)) {
			suffix++;
		}
		return name + "#" + suffix;
	}

	/**
	 * Creates a stable method name when a class or file contains duplicate method names.
	 */
	private String uniqueMethodName(Map<String, Integer> seen, String name) {
		int next = seen.getOrDefault(name, 0) + 1;
		seen.put(name, next);
		return next == 1 ? name : name + "#" + next;
	}

	/**
	 * Uses the file name as a synthetic class name for function-only files.
	 */
	private String fallbackClassName(Path path) {
		String fileName = path.getFileName().toString();
		int dotIndex = fileName.lastIndexOf('.');
		return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
	}

	/**
	 * Maps file extensions to the language names stored in source_files.
	 */
	private Optional<String> detectLanguage(Path path) {
		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
		int dotIndex = fileName.lastIndexOf('.');
		String extension = dotIndex >= 0 ? fileName.substring(dotIndex + 1) : fileName;
		return switch (extension) {
			case "java" -> Optional.of("Java");
			case "kt", "kts" -> Optional.of("Kotlin");
			case "js", "jsx", "mjs", "cjs" -> Optional.of("JavaScript");
			case "ts", "tsx" -> Optional.of("TypeScript");
			case "py" -> Optional.of("Python");
			case "go" -> Optional.of("Go");
			case "rb" -> Optional.of("Ruby");
			case "php" -> Optional.of("PHP");
			case "cs" -> Optional.of("C#");
			case "cpp", "cc", "cxx", "hpp", "h", "c" -> Optional.of("C/C++");
			case "rs" -> Optional.of("Rust");
			case "swift" -> Optional.of("Swift");
			default -> Optional.empty();
		};
	}

	/**
	 * Inserts or updates the repository row and returns its id.
	 */
	private long upsertRepository(String name, String url) {
		return queryForLong("""
				INSERT INTO repositories (name, url)
				VALUES (?, ?)
				ON CONFLICT (url) DO UPDATE
				SET name = EXCLUDED.name, updated_at = NOW()
				RETURNING id
				""", name, url);
	}

	/**
	 * Inserts or updates the current head commit row and returns its id.
	 */
	private long upsertCommit(long repositoryId, GitInfo gitInfo) {
		return queryForLong("""
				INSERT INTO commits (repository_id, sha, author_name, author_email, message, committed_at)
				VALUES (?, ?, ?, ?, ?, ?::timestamptz)
				ON CONFLICT (repository_id, sha) DO UPDATE
				SET author_name = EXCLUDED.author_name,
				    author_email = EXCLUDED.author_email,
				    message = EXCLUDED.message,
				    committed_at = EXCLUDED.committed_at
				RETURNING id
				""", repositoryId, gitInfo.headCommitSha(), gitInfo.authorName(), gitInfo.authorEmail(), gitInfo.message(), gitInfo.committedAt());
	}

	/**
	 * Inserts or updates the current branch row and returns its id.
	 */
	private long upsertBranch(long repositoryId, GitInfo gitInfo) {
		return queryForLong("""
				INSERT INTO branches (repository_id, name, is_default, last_seen_commit_sha, last_scanned_commit_sha)
				SELECT ?, ?, NOT EXISTS (
				    SELECT 1 FROM branches WHERE repository_id = ? AND is_default = TRUE
				), ?, ?
				ON CONFLICT (repository_id, name) DO UPDATE
				SET last_seen_commit_sha = EXCLUDED.last_seen_commit_sha,
				    last_scanned_commit_sha = EXCLUDED.last_scanned_commit_sha,
				    updated_at = NOW()
				RETURNING id
				""", repositoryId, gitInfo.branchName(), repositoryId, gitInfo.headCommitSha(), gitInfo.headCommitSha());
	}

	/**
	 * Records that the current branch contains the parsed head commit.
	 */
	private void linkBranchCommit(long repositoryId, long branchId, long commitId) {
		jdbcTemplate.update("""
				INSERT INTO branch_commits (repository_id, branch_id, commit_id)
				VALUES (?, ?, ?)
				ON CONFLICT (branch_id, commit_id) DO NOTHING
				""", repositoryId, branchId, commitId);
	}

	/**
	 * Creates a running scan row before source files are stored.
	 */
	private long insertScanRun(long repositoryId, long branchId, String headCommitSha) {
		return queryForLong("""
				INSERT INTO scan_runs (repository_id, branch_id, head_commit_sha, status, trigger_type)
				VALUES (?, ?, ?, 'running', 'manual')
				RETURNING id
				""", repositoryId, branchId, headCommitSha);
	}

	/**
	 * Stores one parsed source file and returns its id.
	 */
	private long insertSourceFile(long repositoryId, long branchId, long scanRunId, ParsedSourceFile file) {
		return queryForLong("""
				INSERT INTO source_files (repository_id, branch_id, scan_run_id, path, language, loc)
				VALUES (?, ?, ?, ?, ?, ?)
				RETURNING id
				""", repositoryId, branchId, scanRunId, file.path(), file.language(), file.loc());
	}

	/**
	 * Stores one parsed class and returns its id.
	 */
	private long insertClass(long fileId, ParsedClass parsedClass) {
		return queryForLong("""
				INSERT INTO classes (file_id, name, loc, method_count)
				VALUES (?, ?, ?, ?)
				RETURNING id
				""", fileId, parsedClass.name(), parsedClass.loc(), parsedClass.methods().size());
	}

	/**
	 * Stores one parsed method under its parent class.
	 */
	private void insertMethod(long classId, ParsedMethod method) {
		jdbcTemplate.update("""
				INSERT INTO methods (class_id, name, loc, complexity)
				VALUES (?, ?, ?, ?)
				""", classId, method.name(), method.loc(), method.complexity());
	}

	/**
	 * Stores daily aggregate file, class, and method counts for the repository branch.
	 */
	private void upsertRepositoryMetrics(long repositoryId, long branchId, long scanRunId, int fileCount, int classCount, int methodCount) {
		jdbcTemplate.update("""
				INSERT INTO repository_metrics
				    (repository_id, branch_id, scan_run_id, date, file_count, class_count, method_count, dependency_count)
				VALUES (?, ?, ?, ?, ?, ?, ?, 0)
				ON CONFLICT (repository_id, branch_id, date) DO UPDATE
				SET scan_run_id = EXCLUDED.scan_run_id,
				    file_count = EXCLUDED.file_count,
				    class_count = EXCLUDED.class_count,
				    method_count = EXCLUDED.method_count,
				    dependency_count = EXCLUDED.dependency_count
				""", repositoryId, branchId, scanRunId, LocalDate.now(), fileCount, classCount, methodCount);
	}

	/**
	 * Marks a scan run completed after all parsed records have been stored.
	 */
	private void completeScanRun(long scanRunId) {
		jdbcTemplate.update("UPDATE scan_runs SET status = 'completed', completed_at = NOW() WHERE id = ?", scanRunId);
	}

	/**
	 * Executes an INSERT/UPDATE RETURNING query and validates that an id was returned.
	 */
	private long queryForLong(String sql, Object... args) {
		try {
			Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
			if (id == null) {
				throw new RepositoryScanException("Database did not return an id.");
			}
			return id;
		}
		catch (EmptyResultDataAccessException ex) {
			throw new RepositoryScanException("Database did not return an id.", ex);
		}
	}

	/**
	 * Reads a nullable BIGINT column without converting null to zero.
	 */
	private Long getNullableLong(java.sql.ResultSet rs, String columnName) throws java.sql.SQLException {
		long value = rs.getLong(columnName);
		return rs.wasNull() ? null : value;
	}

	/**
	 * Returns the first non-blank value, otherwise a provided fallback.
	 */
	private String firstNonBlank(String candidate, String fallback) {
		return StringUtils.hasText(candidate) ? candidate.strip() : fallback;
	}

	/**
	 * Converts a local repository path into a file URI for repositories without remotes.
	 */
	private String toFileUri(Path path) {
		URI uri = path.toUri();
		return uri.toString();
	}

	private record GitInfo(
			String branchName,
			String headCommitSha,
			String authorName,
			String authorEmail,
			String message,
			String committedAt) {
	}

	private record ParsedSourceFile(String path, String language, int loc, List<ParsedClass> classes) {
	}

	private record ParsedClass(String name, int loc, List<ParsedMethod> methods) {
	}

	private record ParsedMethod(String name, int loc, int complexity) {
	}

	private static class ParsedClassBuilder {
		private final String name;
		private final List<ParsedMethod> methods = new ArrayList<>();

		/**
		 * Starts a class builder with its parsed or fallback class name.
		 */
		ParsedClassBuilder(String name) {
			this.name = name;
		}

		/**
		 * Adds a detected method to the class being built.
		 */
		void addMethod(String methodName) {
			methods.add(new ParsedMethod(methodName, 0, 0));
		}

		/**
		 * Creates the immutable parsed class record used by persistence code.
		 */
		ParsedClass build(int fileLoc) {
			return new ParsedClass(name, fileLoc, List.copyOf(methods));
		}
	}
}
