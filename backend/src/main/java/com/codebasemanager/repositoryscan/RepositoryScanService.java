package com.codebasemanager.repositoryscan;

import com.codebasemanager.repositoryscan.dto.ParseGitHubRepositoryRequest;
import com.codebasemanager.repositoryscan.dto.ParseRepositoryResponse;
import com.codebasemanager.repositoryscan.dto.GitHubBranchResponse;
import com.codebasemanager.repositoryscan.dto.RepositorySummaryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
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
	private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)");
	private static final Pattern TYPESCRIPT_IMPORT_PATTERN = Pattern.compile("\\bimport\\b[^;]*\\bfrom\\s+['\"]([^'\"]+)['\"]");
	private static final Pattern REQUIRE_PATTERN = Pattern.compile("\\brequire\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
	private static final Pattern PYTHON_IMPORT_PATTERN = Pattern.compile("^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))");
	private static final Pattern SPRING_ROUTE_ANNOTATION_PATTERN = Pattern.compile("@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\s*(?:\\(([^)]*)\\))?");
	private static final Pattern EXPRESS_ROUTE_PATTERN = Pattern.compile("\\b(?:app|router)\\s*\\.\\s*(get|post|put|delete|patch|all)\\s*\\(\\s*['\"]([^'\"]+)['\"]");
	private static final Set<String> DEPENDENCY_FILE_NAMES = Set.of(
			"package.json", "package-lock.json", "requirements.txt", "pyproject.toml",
			"pom.xml", "build.gradle", "build.gradle.kts", "go.mod", "cargo.toml",
			"composer.json", "gemfile");
	private static final List<String> SKIPPED_DIRECTORIES = List.of(
			".git", ".gradle", ".idea", ".vscode", "build", "dist", "node_modules", "out", "target",
			"coverage", ".venv", "venv", "__pycache__");

	private final JdbcTemplate jdbcTemplate;
	private final BranchScanService branchScanService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Receives the JDBC helper used for all parse persistence operations.
	 */
	public RepositoryScanService(JdbcTemplate jdbcTemplate, BranchScanService branchScanService) {
		this.jdbcTemplate = jdbcTemplate;
		this.branchScanService = branchScanService;
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
				       default_branch.name AS default_branch,
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
				LEFT JOIN branches default_branch ON default_branch.repository_id = r.id
				    AND default_branch.id = r.default_branch_id
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
				GROUP BY r.id, default_branch.name, latest_branch.name, latest_scan.id, latest_scan.head_commit_sha,
				         latest_scan.status, latest_scan.completed_at, latest_scan.started_at,
				         latest_metrics.file_count, latest_metrics.class_count, latest_metrics.method_count
				ORDER BY COALESCE(latest_scan.completed_at, latest_scan.started_at, r.updated_at) DESC, r.name ASC
				""", (rs, rowNum) -> new RepositorySummaryResponse(
				rs.getLong("id"),
				rs.getString("name"),
				rs.getString("url"),
				rs.getInt("branch_count"),
				rs.getString("default_branch"),
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
		return branchScanService.listGitHubBranches(url);
	}

	/**
	 * Clones or updates a GitHub repository and stores the parsed snapshot.
	 */
	@Transactional
	public ParseRepositoryResponse parseGitHubAndStore(ParseGitHubRepositoryRequest request) {
		validateGitRepositoryUrl(request.url());
		Path repoPath = cloneOrUpdateRepository(request.url(), firstNonBlank(request.branch(), "main"));
		String repositoryName = firstNonBlank(request.name(), deriveRepositoryName(request.url(), repoPath));
		return parseAndStore(repoPath, repositoryName, request.url());
	}

	/**
	 * Deletes a repository row and relies on database cascades for dependent records.
	 */
	@Transactional
	public void deleteRepository(long repositoryId) {
		int deletedRows = jdbcTemplate.update("DELETE FROM repositories WHERE id = ?", repositoryId);
		if (deletedRows == 0) {
			throw new RepositoryResourceNotFoundException("Repository not found: " + repositoryId);
		}
	}

	/**
	 * Persists Git metadata, parsed source files, classes, methods, and metrics.
	 */
	@Transactional
	protected ParseRepositoryResponse parseAndStore(Path repoPath, String repositoryName, String repositoryUrl) {
		GitInfo gitInfo = readGitInfo(repoPath);

		List<ParsedSourceFile> files = parseSourceFiles(repoPath);
		List<ExternalDependency> externalDependencies = parseExternalDependencies(repoPath);
		long repositoryId = upsertRepository(repositoryName, repositoryUrl);
		long commitId = upsertCommit(repositoryId, gitInfo);
		long branchId = upsertBranch(repositoryId, gitInfo);
		ensureDefaultBranch(repositoryId, branchId);
		linkBranchCommit(repositoryId, branchId, commitId);
		String baseCommitSha = branchScanService.findComparisonBaseCommitSha(repositoryId, branchId).orElse(null);
		long scanRunId = insertScanRun(repositoryId, branchId, baseCommitSha, gitInfo.headCommitSha());

		jdbcTemplate.update("DELETE FROM project_directories WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM external_dependencies WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		jdbcTemplate.update("DELETE FROM source_files WHERE repository_id = ? AND branch_id = ?", repositoryId, branchId);
		insertProjectDirectories(repositoryId, branchId, scanRunId, files);

		int classCount = 0;
		int methodCount = 0;
		Map<String, Long> fileIdsByPath = new HashMap<>();
		Map<String, Long> classIdsByName = new HashMap<>();
		Map<String, Long> classIdsByPathAndName = new HashMap<>();
		Map<String, Long> methodIdsByPathClassAndName = new HashMap<>();
		for (ParsedSourceFile file : files) {
			long fileId = insertSourceFile(repositoryId, branchId, scanRunId, file);
			fileIdsByPath.put(file.path(), fileId);
			classCount += file.classes().size();
			for (ParsedClass parsedClass : file.classes()) {
				long classId = insertClass(fileId, parsedClass);
				classIdsByName.putIfAbsent(parsedClass.name(), classId);
				classIdsByPathAndName.put(classKey(file.path(), parsedClass.name()), classId);
				methodCount += parsedClass.methods().size();
				for (ParsedMethod method : parsedClass.methods()) {
					long methodId = insertMethod(classId, method);
					methodIdsByPathClassAndName.put(methodKey(file.path(), parsedClass.name(), method.name()), methodId);
				}
			}
		}

		int dependencyCount = insertInternalDependencies(files, classIdsByName, classIdsByPathAndName);
		int apiRouteCount = insertApiRoutes(repositoryId, branchId, scanRunId, files, fileIdsByPath, classIdsByPathAndName, methodIdsByPathClassAndName);
		int externalDependencyCount = insertExternalDependencies(repositoryId, branchId, scanRunId, externalDependencies);

		upsertRepositoryMetrics(repositoryId, branchId, scanRunId, files.size(), classCount, methodCount, dependencyCount + externalDependencyCount);
		branchScanService.insertFileChanges(repoPath, scanRunId, baseCommitSha, gitInfo.headCommitSha());
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
				methodCount,
				dependencyCount,
				externalDependencyCount,
				apiRouteCount);
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
			branchScanService.checkoutRequestedBranch(cachePath, branch);
			return cachePath;
		}

		List<String> cloneArgs = new ArrayList<>(List.of("clone", url, cachePath.toString()));
		runGitCommand(Path.of("."), cloneArgs);
		branchScanService.checkoutRequestedBranch(cachePath, branch);
		return cachePath;
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
		List<String> imports = parseImports(language, lines);
		List<ParsedApiRoute> apiRoutes = parseApiRoutes(language, lines, classes);
		return new ParsedSourceFile(relativePath, language, loc, classes, imports, apiRoutes);
	}

	/**
	 * Extracts imports that can be resolved to internal class dependency edges.
	 */
	private List<String> parseImports(String language, List<String> lines) {
		List<String> imports = new ArrayList<>();
		for (String line : lines) {
			Matcher matcher = switch (language) {
				case "Java", "Kotlin" -> JAVA_IMPORT_PATTERN.matcher(line);
				case "JavaScript", "TypeScript" -> TYPESCRIPT_IMPORT_PATTERN.matcher(line);
				case "Python" -> PYTHON_IMPORT_PATTERN.matcher(line);
				default -> REQUIRE_PATTERN.matcher(line);
			};
			while (matcher.find()) {
				String imported = firstNonBlank(matcher.group(1), matcher.groupCount() > 1 ? matcher.group(2) : null);
				if (StringUtils.hasText(imported)) {
					imports.add(imported);
				}
			}
			if ("JavaScript".equals(language) || "TypeScript".equals(language)) {
				Matcher requireMatcher = REQUIRE_PATTERN.matcher(line);
				while (requireMatcher.find()) {
					imports.add(requireMatcher.group(1));
				}
			}
		}
		return imports;
	}

	/**
	 * Extracts common backend API route declarations for baseline risk inventory.
	 */
	private List<ParsedApiRoute> parseApiRoutes(String language, List<String> lines, List<ParsedClass> classes) {
		if ("Java".equals(language) || "Kotlin".equals(language)) {
			return parseSpringApiRoutes(lines, classes);
		}
		if ("JavaScript".equals(language) || "TypeScript".equals(language)) {
			return parseExpressApiRoutes(lines, classes);
		}
		return List.of();
	}

	/**
	 * Extracts Spring mapping annotations and attaches them to the next detected method.
	 */
	private List<ParsedApiRoute> parseSpringApiRoutes(List<String> lines, List<ParsedClass> classes) {
		List<ParsedApiRoute> routes = new ArrayList<>();
		String className = classes.isEmpty() ? null : classes.get(0).name();
		String classBasePath = "";
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			Matcher matcher = SPRING_ROUTE_ANNOTATION_PATTERN.matcher(line);
			while (matcher.find()) {
				String httpMethod = springHttpMethod(matcher.group(1));
				String routePath = extractAnnotationPath(matcher.group(2));
				if ("ANY".equals(httpMethod) && line.contains("class ")) {
					classBasePath = normalizeRoutePath(routePath);
					continue;
				}
				String handlerMethod = findNextMethodName(lines, index + 1);
				routes.add(new ParsedApiRoute(
						httpMethod,
						joinRoutePaths(classBasePath, routePath),
						className,
						handlerMethod,
						index + 1));
			}
		}
		return routes;
	}

	/**
	 * Extracts Express-style router/app route declarations.
	 */
	private List<ParsedApiRoute> parseExpressApiRoutes(List<String> lines, List<ParsedClass> classes) {
		List<ParsedApiRoute> routes = new ArrayList<>();
		String className = classes.isEmpty() ? null : classes.get(0).name();
		for (int index = 0; index < lines.size(); index++) {
			Matcher matcher = EXPRESS_ROUTE_PATTERN.matcher(lines.get(index));
			while (matcher.find()) {
				routes.add(new ParsedApiRoute(
						matcher.group(1).toUpperCase(Locale.ROOT),
						normalizeRoutePath(matcher.group(2)),
						className,
						findNearbyHandlerName(lines.get(index)),
						index + 1));
			}
		}
		return routes;
	}

	private String springHttpMethod(String annotation) {
		return switch (annotation) {
			case "GetMapping" -> "GET";
			case "PostMapping" -> "POST";
			case "PutMapping" -> "PUT";
			case "DeleteMapping" -> "DELETE";
			case "PatchMapping" -> "PATCH";
			default -> "ANY";
		};
	}

	private String extractAnnotationPath(String annotationArgs) {
		if (!StringUtils.hasText(annotationArgs)) {
			return "/";
		}
		Matcher matcher = Pattern.compile("(?:path|value)?\\s*=*\\s*\\{?\\s*\"([^\"]+)\"").matcher(annotationArgs);
		return matcher.find() ? matcher.group(1) : "/";
	}

	private String findNextMethodName(List<String> lines, int startIndex) {
		for (int index = startIndex; index < Math.min(lines.size(), startIndex + 8); index++) {
			for (Pattern pattern : List.of(JAVA_LIKE_METHOD_PATTERN, FUNCTION_PATTERN, ARROW_FUNCTION_PATTERN)) {
				Matcher matcher = pattern.matcher(lines.get(index));
				if (matcher.find() && !isControlKeyword(matcher.group(1))) {
					return matcher.group(1);
				}
			}
		}
		return null;
	}

	private String findNearbyHandlerName(String line) {
		Matcher matcher = Pattern.compile(",\\s*([A-Za-z_$][\\w$]*)\\s*(?:\\)|,)").matcher(line);
		return matcher.find() ? matcher.group(1) : null;
	}

	private String joinRoutePaths(String basePath, String routePath) {
		String normalizedBasePath = normalizeRoutePath(basePath);
		String normalizedRoutePath = normalizeRoutePath(routePath);
		if ("/".equals(normalizedBasePath)) {
			return normalizedRoutePath;
		}
		if ("/".equals(normalizedRoutePath)) {
			return normalizedBasePath;
		}
		return normalizeRoutePath(normalizedBasePath + "/" + normalizedRoutePath);
	}

	private String normalizeRoutePath(String path) {
		if (!StringUtils.hasText(path)) {
			return "/";
		}
		String normalized = path.strip();
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}
		return normalized.replaceAll("/{2,}", "/");
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
	 * Walks recognized dependency manifests and extracts external package inventory.
	 */
	private List<ExternalDependency> parseExternalDependencies(Path repoPath) {
		try (var stream = Files.walk(repoPath)) {
			return stream
					.filter(Files::isRegularFile)
					.filter(path -> !isSkipped(repoPath, path))
					.filter(this::isDependencyManifest)
					.flatMap(path -> parseExternalDependencyManifest(repoPath, path).stream())
					.toList();
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to walk dependency manifests.", ex);
		}
	}

	private boolean isDependencyManifest(Path path) {
		return DEPENDENCY_FILE_NAMES.contains(path.getFileName().toString().toLowerCase(Locale.ROOT));
	}

	private List<ExternalDependency> parseExternalDependencyManifest(Path repoPath, Path path) {
		String relativePath = repoPath.relativize(path).toString().replace('\\', '/');
		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
		try {
			return switch (fileName) {
				case "package.json" -> parsePackageJson(relativePath, Files.readString(path, StandardCharsets.UTF_8));
				case "requirements.txt" -> parseRequirementsTxt(relativePath, Files.readAllLines(path, StandardCharsets.UTF_8));
				case "pom.xml" -> parseRegexDependencies(relativePath, "maven", Files.readString(path, StandardCharsets.UTF_8), "<artifactId>([^<]+)</artifactId>", "compile");
				case "build.gradle", "build.gradle.kts" -> parseRegexDependencies(relativePath, "gradle", Files.readString(path, StandardCharsets.UTF_8), "['\"]([^:'\"]+:[^:'\"]+):([^'\"]+)['\"]", "implementation");
				case "go.mod" -> parseRegexDependencies(relativePath, "go", Files.readString(path, StandardCharsets.UTF_8), "^\\s*([\\w./-]+)\\s+v([^\\s]+)", "require");
				case "cargo.toml" -> parseRegexDependencies(relativePath, "cargo", Files.readString(path, StandardCharsets.UTF_8), "^\\s*([A-Za-z0-9_-]+)\\s*=", "dependencies");
				case "composer.json" -> parseComposerJson(relativePath, Files.readString(path, StandardCharsets.UTF_8));
				case "gemfile" -> parseRegexDependencies(relativePath, "bundler", Files.readString(path, StandardCharsets.UTF_8), "^\\s*gem\\s+['\"]([^'\"]+)['\"]\\s*(?:,\\s*['\"]([^'\"]+)['\"])?", "runtime");
				default -> List.of();
			};
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to read dependency manifest: " + relativePath, ex);
		}
	}

	private List<ExternalDependency> parsePackageJson(String manifestPath, String content) {
		try {
			JsonNode root = objectMapper.readTree(content);
			List<ExternalDependency> dependencies = new ArrayList<>();
			for (String scope : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
				JsonNode node = root.path(scope);
				if (node.isObject()) {
					node.fields().forEachRemaining(entry -> dependencies.add(new ExternalDependency(
							manifestPath,
							"npm",
							entry.getKey(),
							entry.getValue().asText(null),
							scope)));
				}
			}
			return dependencies;
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to parse package.json: " + manifestPath, ex);
		}
	}

	private List<ExternalDependency> parseComposerJson(String manifestPath, String content) {
		try {
			JsonNode root = objectMapper.readTree(content);
			List<ExternalDependency> dependencies = new ArrayList<>();
			for (String scope : List.of("require", "require-dev")) {
				JsonNode node = root.path(scope);
				if (node.isObject()) {
					node.fields().forEachRemaining(entry -> dependencies.add(new ExternalDependency(
							manifestPath,
							"composer",
							entry.getKey(),
							entry.getValue().asText(null),
							scope)));
				}
			}
			return dependencies;
		}
		catch (IOException ex) {
			throw new RepositoryScanException("Unable to parse composer.json: " + manifestPath, ex);
		}
	}

	private List<ExternalDependency> parseRequirementsTxt(String manifestPath, List<String> lines) {
		List<ExternalDependency> dependencies = new ArrayList<>();
		Pattern requirementPattern = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*([<>=!~].+)?$");
		for (String line : lines) {
			String cleaned = line.split("#", 2)[0].strip();
			if (!StringUtils.hasText(cleaned) || cleaned.startsWith("-")) {
				continue;
			}
			Matcher matcher = requirementPattern.matcher(cleaned);
			if (matcher.find()) {
				dependencies.add(new ExternalDependency(manifestPath, "pip", matcher.group(1), matcher.group(2), "runtime"));
			}
		}
		return dependencies;
	}

	private List<ExternalDependency> parseRegexDependencies(
			String manifestPath,
			String manager,
			String content,
			String regex,
			String scope) {
		List<ExternalDependency> dependencies = new ArrayList<>();
		Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(content);
		while (matcher.find()) {
			String packageName = matcher.group(1);
			String versionSpec = matcher.groupCount() > 1 ? matcher.group(2) : null;
			dependencies.add(new ExternalDependency(manifestPath, manager, packageName, versionSpec, scope));
		}
		return dependencies;
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
				VALUES (?, ?, FALSE, ?, ?)
				ON CONFLICT (repository_id, name) DO UPDATE
				SET last_seen_commit_sha = EXCLUDED.last_seen_commit_sha,
				    last_scanned_commit_sha = EXCLUDED.last_scanned_commit_sha,
				    updated_at = NOW()
				RETURNING id
				""", repositoryId, gitInfo.branchName(), gitInfo.headCommitSha(), gitInfo.headCommitSha());
	}

	/**
	 * Ensures every repository has one direct default branch pointer.
	 */
	private void ensureDefaultBranch(long repositoryId, long fallbackBranchId) {
		jdbcTemplate.update("""
				UPDATE repositories r
				SET default_branch_id = COALESCE(
				        (
				            SELECT b.id
				            FROM branches b
				            WHERE b.repository_id = r.id
				              AND b.is_default = TRUE
				            ORDER BY b.id
				            LIMIT 1
				        ),
				        ?
				    ),
				    updated_at = NOW()
				WHERE r.id = ?
				  AND r.default_branch_id IS NULL
				""", fallbackBranchId, repositoryId);
		syncDefaultBranchFlag(repositoryId);
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
	private long insertScanRun(long repositoryId, long branchId, String baseCommitSha, String headCommitSha) {
		return queryForLong("""
				INSERT INTO scan_runs (repository_id, branch_id, base_commit_sha, head_commit_sha, status, trigger_type)
				VALUES (?, ?, ?, ?, 'running', 'manual')
				RETURNING id
				""", repositoryId, branchId, baseCommitSha, headCommitSha);
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
	private long insertMethod(long classId, ParsedMethod method) {
		return queryForLong("""
				INSERT INTO methods (class_id, name, loc, complexity)
				VALUES (?, ?, ?, ?)
				RETURNING id
				""", classId, method.name(), method.loc(), method.complexity());
	}

	/**
	 * Stores branch directory inventory derived from source file paths.
	 */
	private void insertProjectDirectories(long repositoryId, long branchId, long scanRunId, List<ParsedSourceFile> files) {
		Set<String> directories = new java.util.TreeSet<>();
		for (ParsedSourceFile file : files) {
			String[] parts = file.path().split("/");
			StringBuilder directory = new StringBuilder();
			for (int index = 0; index < parts.length - 1; index++) {
				if (directory.length() > 0) {
					directory.append('/');
				}
				directory.append(parts[index]);
				directories.add(directory.toString());
			}
		}
		for (String directory : directories) {
			jdbcTemplate.update("""
					INSERT INTO project_directories (repository_id, branch_id, scan_run_id, path, parent_path, depth)
					VALUES (?, ?, ?, ?, ?, ?)
					ON CONFLICT (repository_id, branch_id, path) DO UPDATE
					SET scan_run_id = EXCLUDED.scan_run_id,
					    parent_path = EXCLUDED.parent_path,
					    depth = EXCLUDED.depth
					""", repositoryId, branchId, scanRunId, directory, parentDirectory(directory), directoryDepth(directory));
		}
	}

	/**
	 * Stores internal class dependency edges resolved from parsed imports.
	 */
	private int insertInternalDependencies(
			List<ParsedSourceFile> files,
			Map<String, Long> classIdsByName,
			Map<String, Long> classIdsByPathAndName) {
		int dependencyCount = 0;
		for (ParsedSourceFile file : files) {
			for (ParsedClass parsedClass : file.classes()) {
				Long sourceClassId = classIdsByPathAndName.get(classKey(file.path(), parsedClass.name()));
				if (sourceClassId == null) {
					continue;
				}
				for (String imported : file.imports()) {
					Long targetClassId = resolveImportedClass(imported, classIdsByName);
					if (targetClassId != null && !targetClassId.equals(sourceClassId)) {
						dependencyCount += jdbcTemplate.update("""
								INSERT INTO dependencies (source_class, target_class, dependency_type)
								VALUES (?, ?, 'import')
								ON CONFLICT (source_class, target_class, dependency_type) DO NOTHING
								""", sourceClassId, targetClassId);
					}
				}
			}
		}
		return dependencyCount;
	}

	private Long resolveImportedClass(String imported, Map<String, Long> classIdsByName) {
		String candidate = imported;
		int dotIndex = candidate.lastIndexOf('.');
		if (dotIndex >= 0) {
			candidate = candidate.substring(dotIndex + 1);
		}
		int slashIndex = candidate.lastIndexOf('/');
		if (slashIndex >= 0) {
			candidate = candidate.substring(slashIndex + 1);
		}
		return classIdsByName.get(candidate);
	}

	/**
	 * Stores API route inventory derived from source files.
	 */
	private int insertApiRoutes(
			long repositoryId,
			long branchId,
			long scanRunId,
			List<ParsedSourceFile> files,
			Map<String, Long> fileIdsByPath,
			Map<String, Long> classIdsByPathAndName,
			Map<String, Long> methodIdsByPathClassAndName) {
		int routeCount = 0;
		for (ParsedSourceFile file : files) {
			Long fileId = fileIdsByPath.get(file.path());
			for (ParsedApiRoute route : file.apiRoutes()) {
				Long classId = route.handlerClass() == null ? null : classIdsByPathAndName.get(classKey(file.path(), route.handlerClass()));
				Long methodId = route.handlerClass() == null || route.handlerMethod() == null
						? null
						: methodIdsByPathClassAndName.get(methodKey(file.path(), route.handlerClass(), route.handlerMethod()));
				routeCount += jdbcTemplate.update("""
						INSERT INTO api_routes
						    (repository_id, branch_id, scan_run_id, file_id, class_id, method_id, http_method, route_path, handler_class, handler_method, line_number)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						ON CONFLICT (repository_id, branch_id, http_method, route_path, file_id) DO UPDATE
						SET scan_run_id = EXCLUDED.scan_run_id,
						    class_id = EXCLUDED.class_id,
						    method_id = EXCLUDED.method_id,
						    handler_class = EXCLUDED.handler_class,
						    handler_method = EXCLUDED.handler_method,
						    line_number = EXCLUDED.line_number
						""", repositoryId, branchId, scanRunId, fileId, classId, methodId,
						route.httpMethod(), route.path(), route.handlerClass(), route.handlerMethod(), route.lineNumber());
			}
		}
		return routeCount;
	}

	/**
	 * Stores external package inventory from dependency manifests.
	 */
	private int insertExternalDependencies(
			long repositoryId,
			long branchId,
			long scanRunId,
			List<ExternalDependency> dependencies) {
		int dependencyCount = 0;
		for (ExternalDependency dependency : dependencies) {
			dependencyCount += jdbcTemplate.update("""
					INSERT INTO external_dependencies
					    (repository_id, branch_id, scan_run_id, manifest_path, manager, package_name, version_spec, dependency_scope)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (repository_id, branch_id, manifest_path, manager, package_name, dependency_scope) DO UPDATE
					SET scan_run_id = EXCLUDED.scan_run_id,
					    version_spec = EXCLUDED.version_spec
					""", repositoryId, branchId, scanRunId, dependency.manifestPath(), dependency.manager(),
					dependency.packageName(), dependency.versionSpec(), dependency.scope());
		}
		return dependencyCount;
	}

	/**
	 * Stores daily aggregate file, class, and method counts for the repository branch.
	 */
	private void upsertRepositoryMetrics(
			long repositoryId,
			long branchId,
			long scanRunId,
			int fileCount,
			int classCount,
			int methodCount,
			int dependencyCount) {
		jdbcTemplate.update("""
				INSERT INTO repository_metrics
				    (repository_id, branch_id, scan_run_id, date, file_count, class_count, method_count, dependency_count)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (repository_id, branch_id, date) DO UPDATE
				SET scan_run_id = EXCLUDED.scan_run_id,
				    file_count = EXCLUDED.file_count,
				    class_count = EXCLUDED.class_count,
				    method_count = EXCLUDED.method_count,
				    dependency_count = EXCLUDED.dependency_count
				""", repositoryId, branchId, scanRunId, LocalDate.now(), fileCount, classCount, methodCount, dependencyCount);
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

	private String parentDirectory(String directory) {
		int slashIndex = directory.lastIndexOf('/');
		return slashIndex > 0 ? directory.substring(0, slashIndex) : null;
	}

	private int directoryDepth(String directory) {
		return (int) directory.chars().filter(character -> character == '/').count();
	}

	private String classKey(String path, String className) {
		return path + "#" + className;
	}

	private String methodKey(String path, String className, String methodName) {
		return path + "#" + className + "." + methodName;
	}

	private record GitInfo(
			String branchName,
			String headCommitSha,
			String authorName,
			String authorEmail,
			String message,
			String committedAt) {
	}

	private record ParsedSourceFile(
			String path,
			String language,
			int loc,
			List<ParsedClass> classes,
			List<String> imports,
			List<ParsedApiRoute> apiRoutes) {
	}

	private record ParsedClass(String name, int loc, List<ParsedMethod> methods) {
	}

	private record ParsedMethod(String name, int loc, int complexity) {
	}

	private record ParsedApiRoute(
			String httpMethod,
			String path,
			String handlerClass,
			String handlerMethod,
			int lineNumber) {
	}

	private record ExternalDependency(
			String manifestPath,
			String manager,
			String packageName,
			String versionSpec,
			String scope) {
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
