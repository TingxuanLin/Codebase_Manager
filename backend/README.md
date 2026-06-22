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
```

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
