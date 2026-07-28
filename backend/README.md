# Codebase Manager

Spring Boot backend service for Codebase Manager.

## Requirements

- JDK 17
- Gradle wrapper included in this repository
- Docker, for local PostgreSQL

## ERD
<img width="1536" height="1024" alt="ChatGPT Image Jun 22, 2026, 04_57_15 PM" src="https://github.com/user-attachments/assets/376e7541-4dca-4996-84c0-c3a57f39a6d6" />


## PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

The local database connection is:

```text
jdbc:postgresql://localhost:5433/codebase_manager
username: codebase_manager
password: codebase_manager
```

Schema changes are managed by Flyway migrations in `src/main/resources/db/migration` when the backend starts.

## Run

```bash
./gradlew bootRun
```

The app starts on:

```text
http://localhost:8080
```

Useful endpoints:

```text
GET /
GET /actuator/health
GET /repositories
POST /repositories/parse-github
GET /repositories/github-branches?url={githubRepositoryUrl}
PATCH /repositories/{repositoryId}/branches/{branchId}/default
GET /repositories/{repositoryId}/branches/{branchId}/comparison
DELETE /repositories/{repositoryId}/branches/{branchId}
POST /repositories/{repositoryId}/pull-requests/check
GET /repositories/{repositoryId}/pull-requests
GET /repositories/{repositoryId}/pull-requests/all
GET /repositories/{repositoryId}/pull-requests/{pullRequestNumber}/diff
GET /repositories/{repositoryId}/pull-requests/{pullRequestNumber}/risk
```

## Parse and Store a Repository

The parser stores repository, branch, commit, scan run, source file, class, method, route, dependency, directory, and metric rows.
When a non-default branch is parsed, the scan run is compared against the configured default branch commit and file-level changes are stored in `file_changes`.

### GitHub Repository

Use this when you want the backend to clone or fetch the repository before parsing.

```bash
curl -X POST http://localhost:8080/repositories/parse-github \
  -H 'Content-Type: application/json' \
  -d '{
    "url": "https://github.com/prospectequities-tech/pe-website-backend",
    "branch": "main",
    "name": "pe-website-backend"
  }'
```

`branch` and `name` are optional. If `branch` is omitted, the backend checks out the remote default branch. Repositories are cached under `~/.codebase-manager/repositories` by default. Set `CODEBASE_REPOSITORY_CACHE_DIR` to use another location.

For private repositories, configure local Git credentials, SSH keys, or a credential manager for the backend process.

### Pull Requests

Check GitHub for open pull requests targeting the stored default branch and persist newly seen PRs:

```bash
curl -X POST http://localhost:8080/repositories/1/pull-requests/check
```

When a PR is new or its head SHA changes, the backend stores file-level diff metadata and patch text in `pull_request_file_changes`.

Read the stored diff with:

```bash
curl http://localhost:8080/repositories/1/pull-requests/7/diff
```

Run the basic rule-based risk analysis with:

```bash
curl http://localhost:8080/repositories/1/pull-requests/7/risk
```

The MVP risk analyzer uses stored PR file changes plus parsed repository inventory. It scores change volume, code churn, dependency manifest edits, sensitive paths, deleted files, API route impact, large files, and internal dependency fanout.

### Set Default Branch

The first scanned branch becomes the default branch automatically. The repository stores that as `repositories.default_branch_id`, while `branches.is_default` is kept synchronized for easy display and filtering. Use this endpoint to change the comparison base, for example to mark `main` as the default after it has been scanned:

```bash
curl -X PATCH http://localhost:8080/repositories/1/branches/2/default
```

After that, scans of other branches in the same repository will store `scan_runs.base_commit_sha` from the default branch and persist changed files in `file_changes`.

### Compare Branch With Default

The latest completed scan for a branch can be read with:

```bash
curl http://localhost:8080/repositories/1/branches/2/comparison
```

The response includes the default branch name, base and head commit SHAs, total additions/deletions, and changed files.

Supported source file extensions include Java, Kotlin, JavaScript, TypeScript, Python, Go, Ruby, PHP, C#, C/C++, Rust, and Swift.

Configuration lives in:

```text
src/main/resources/application.yml
```

Initial database schema:

```text
src/main/resources/db/migration/V1__initial_schema.sql
```

Add new schema changes as a new migration file:

```text
src/main/resources/db/migration/V6__description.sql
src/main/resources/db/migration/V7__description.sql
```

Do not edit a migration after it has already run locally. Create the next version instead.

## Test

```bash
./gradlew test
```

## IntelliJ Setup

Set the project SDK and Gradle JVM to JDK 17:

```text
File -> Project Structure -> Project SDK
Settings -> Build, Execution, Deployment -> Build Tools -> Gradle -> Gradle JVM
```
