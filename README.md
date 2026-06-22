# Codebase Manager

Codebase Manager is an AI-powered platform designed to help developers and teams organize, understand, and interact with large codebases through natural language.

Modern software projects often span multiple repositories, technologies, and teams, making it difficult to quickly locate business logic, understand architecture, and onboard new contributors. Codebase Manager solves this problem by automatically indexing repositories, analyzing code relationships, generating semantic embeddings, and enabling intelligent search and AI-assisted exploration.

## Vision

Transform software repositories into a searchable knowledge base where developers can:

* Search code using natural language
* Understand system architecture instantly
* Discover dependencies and code relationships
* Generate AI-powered summaries and documentation
* Monitor repository changes in real time
* Ask questions about the codebase and receive contextual answers

## Key Features

### Repository Management

* Connect local and remote repositories
* Automatic code indexing and synchronization
* Multi-repository workspace support

### Semantic Search

* Vector-based code search
* Natural language queries
* Cross-file and cross-repository discovery

### AI Code Understanding

* Repository summarization
* File and module explanations
* Architecture visualization
* Context-aware code navigation

### Real-Time Monitoring

* Git webhook integration
* Incremental indexing
* Automatic knowledge base updates

### Developer Copilot

* Ask questions about the codebase
* Locate business logic instantly
* Generate onboarding documentation
* Explain system workflows and dependencies

## Target Users

* Software Engineers
* Engineering Managers
* Technical Leads
* Startups managing multiple repositories
* Teams onboarding new developers

## Long-Term Goal

Build a centralized AI knowledge layer for software development that allows engineers to interact with codebases as easily as searching the web.

## Structure

    frontend/
    backend/

The Spring Boot backend is in `backend/`.
The React frontend is in `frontend/`.

## Local Development

### Start PostgreSQL

    docker compose up -d postgres

### Run Backend

    cd backend
    ./gradlew bootRun

### Run Frontend

    cd frontend
    npm install
    npm run dev

The frontend starts on:

    http://localhost:5173
