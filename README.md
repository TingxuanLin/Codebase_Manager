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
