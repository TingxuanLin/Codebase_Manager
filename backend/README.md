# Codebase Manager

Spring Boot backend service for Codebase Manager.

## Requirements

- JDK 17
- Gradle wrapper included in this repository

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
