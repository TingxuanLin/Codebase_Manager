# Codebase Manager

Monorepo for the Codebase Manager application.

## Structure

```text
frontend/
backend/
```

The Spring Boot backend is in `backend/`.
The React frontend is in `frontend/`.

## Start PostgreSQL

```bash
docker compose up -d postgres
```

## Run Backend

```bash
cd backend
./gradlew bootRun
```

## Run Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on:

```text
http://localhost:5173
```

## Repository Parsing

Use the frontend form or call the backend directly.

For a local clone:

```bash
curl -X POST http://localhost:8080/repositories/parse-local \
  -H 'Content-Type: application/json' \
  -d '{"path":"/absolute/path/to/git/repo"}'
```

For a GitHub repo URL:

```bash
curl -X POST http://localhost:8080/repositories/parse-github \
  -H 'Content-Type: application/json' \
  -d '{
    "url": "https://github.com/prospectequities-tech/pe-website-backend",
    "branch": "main"
  }'
```

The backend stores repository metadata, current branch and head commit, scan run status, source files, detected classes, detected methods, and daily repository metrics in PostgreSQL.

GitHub repositories are cloned or fetched into `~/.codebase-manager/repositories` by default. Set `CODEBASE_REPOSITORY_CACHE_DIR` to override the cache directory.
