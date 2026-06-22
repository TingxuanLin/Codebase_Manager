# Codebase Manager

Spring Boot backend service for Codebase Manager.

## Requirements

- JDK 17
- Gradle wrapper included in this repository
- Docker, for local PostgreSQL

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

The schema in `src/main/resources/db/init.sql` is loaded automatically the first time the Docker volume is created.

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
POST /repositories/parse-local
POST /repositories/parse-github
```

## Parse and Store a Repository

The parser stores repository, branch, commit, scan run, source file, class, method, and metric rows.

### Local Repository

Use this when the repository already exists on the machine running the backend.

```bash
curl -X POST http://localhost:8080/repositories/parse-local \
  -H 'Content-Type: application/json' \
  -d '{
    "path": "/absolute/path/to/repo",
    "name": "Optional display name",
    "url": "Optional canonical repository URL"
  }'
```

`name` defaults to the folder name. `url` defaults to `remote.origin.url`, then the local file URI if no origin exists.

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

Supported source file extensions include Java, Kotlin, JavaScript, TypeScript, Python, Go, Ruby, PHP, C#, C/C++, Rust, and Swift.

Configuration lives in:

```text
src/main/resources/application.yml
```

Initial database schema:

```text
src/main/resources/db/init.sql
```

To run it manually against an existing PostgreSQL database:

```bash
psql "$DATABASE_URL" -f src/main/resources/db/init.sql
```

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
