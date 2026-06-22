# Codebase Manager

Monorepo for the Codebase Manager application.

## Structure

```text
frontend/
backend/
```

The Spring Boot backend is in `backend/`.

## Start PostgreSQL

```bash
docker compose up -d postgres
```

## Run Backend

```bash
cd backend
./gradlew bootRun
```
