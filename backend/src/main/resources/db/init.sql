CREATE TABLE IF NOT EXISTS repositories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_repositories_url UNIQUE (url)
);

CREATE TABLE IF NOT EXISTS branches (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_commit_sha VARCHAR(64),
    last_scanned_commit_sha VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_branches_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_branches_repository_name UNIQUE (repository_id, name),
    CONSTRAINT uq_branches_repository_id UNIQUE (repository_id, id)
);

CREATE TABLE IF NOT EXISTS commits (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    sha VARCHAR(64) NOT NULL,
    author_name VARCHAR(255),
    author_email VARCHAR(320),
    message TEXT,
    committed_at TIMESTAMPTZ,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_commits_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_commits_repository_sha UNIQUE (repository_id, sha),
    CONSTRAINT uq_commits_repository_id UNIQUE (repository_id, id),
    CONSTRAINT chk_commits_sha_not_blank CHECK (length(trim(sha)) > 0)
);

CREATE TABLE IF NOT EXISTS branch_commits (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    commit_id BIGINT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_branch_commits_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_branch_commits_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_branch_commits_commit
        FOREIGN KEY (repository_id, commit_id)
        REFERENCES commits (repository_id, id)
        ON DELETE CASCADE,
    CONSTRAINT uq_branch_commits_branch_commit UNIQUE (branch_id, commit_id)
);

CREATE TABLE IF NOT EXISTS scan_runs (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    base_commit_sha VARCHAR(64),
    head_commit_sha VARCHAR(64) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    trigger_type VARCHAR(50) NOT NULL DEFAULT 'manual',
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    CONSTRAINT fk_scan_runs_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_scan_runs_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_scan_runs_base_commit
        FOREIGN KEY (repository_id, base_commit_sha)
        REFERENCES commits (repository_id, sha),
    CONSTRAINT fk_scan_runs_head_commit
        FOREIGN KEY (repository_id, head_commit_sha)
        REFERENCES commits (repository_id, sha),
    CONSTRAINT chk_scan_runs_status CHECK (status IN ('pending', 'running', 'completed', 'failed', 'cancelled')),
    CONSTRAINT chk_scan_runs_trigger_type CHECK (trigger_type IN ('manual', 'scheduled', 'webhook')),
    CONSTRAINT chk_scan_runs_head_commit_sha_not_blank CHECK (length(trim(head_commit_sha)) > 0),
    CONSTRAINT chk_scan_runs_completed_after_started CHECK (completed_at IS NULL OR completed_at >= started_at)
);

CREATE TABLE IF NOT EXISTS file_changes (
    id BIGSERIAL PRIMARY KEY,
    scan_run_id BIGINT NOT NULL,
    path TEXT NOT NULL,
    old_path TEXT,
    change_type VARCHAR(50) NOT NULL,
    additions INTEGER NOT NULL DEFAULT 0,
    deletions INTEGER NOT NULL DEFAULT 0,
    patch_uri TEXT,
    CONSTRAINT fk_file_changes_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_file_changes_scan_run_path UNIQUE (scan_run_id, path),
    CONSTRAINT chk_file_changes_change_type CHECK (change_type IN ('added', 'modified', 'deleted', 'renamed', 'copied')),
    CONSTRAINT chk_file_changes_additions_non_negative CHECK (additions >= 0),
    CONSTRAINT chk_file_changes_deletions_non_negative CHECK (deletions >= 0),
    CONSTRAINT chk_file_changes_path_not_blank CHECK (length(trim(path)) > 0)
);

CREATE TABLE IF NOT EXISTS analysis_artifacts (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT,
    scan_run_id BIGINT,
    artifact_type VARCHAR(50) NOT NULL,
    format VARCHAR(50) NOT NULL,
    uri TEXT NOT NULL,
    content_hash VARCHAR(128),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_analysis_artifacts_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_analysis_artifacts_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE SET NULL (branch_id),
    CONSTRAINT fk_analysis_artifacts_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_analysis_artifacts_uri UNIQUE (uri),
    CONSTRAINT chk_analysis_artifacts_type CHECK (artifact_type IN ('llm_context', 'summary', 'diff_patch', 'metrics', 'risk_report')),
    CONSTRAINT chk_analysis_artifacts_format CHECK (format IN ('json', 'markdown', 'patch', 'text')),
    CONSTRAINT chk_analysis_artifacts_uri_not_blank CHECK (length(trim(uri)) > 0)
);

CREATE TABLE IF NOT EXISTS source_files (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT,
    scan_run_id BIGINT,
    path TEXT NOT NULL,
    language VARCHAR(100),
    loc INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_source_files_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_source_files_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE SET NULL (branch_id),
    CONSTRAINT fk_source_files_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE SET NULL,
    CONSTRAINT uq_source_files_repository_branch_path UNIQUE NULLS NOT DISTINCT (repository_id, branch_id, path),
    CONSTRAINT chk_source_files_loc_non_negative CHECK (loc >= 0),
    CONSTRAINT chk_source_files_path_not_blank CHECK (length(trim(path)) > 0)
);

CREATE TABLE IF NOT EXISTS classes (
    id BIGSERIAL PRIMARY KEY,
    file_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    loc INTEGER NOT NULL DEFAULT 0,
    method_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_classes_file
        FOREIGN KEY (file_id)
        REFERENCES source_files (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_classes_file_name UNIQUE (file_id, name),
    CONSTRAINT chk_classes_loc_non_negative CHECK (loc >= 0),
    CONSTRAINT chk_classes_method_count_non_negative CHECK (method_count >= 0)
);

CREATE TABLE IF NOT EXISTS methods (
    id BIGSERIAL PRIMARY KEY,
    class_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    loc INTEGER NOT NULL DEFAULT 0,
    complexity INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_methods_class
        FOREIGN KEY (class_id)
        REFERENCES classes (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_methods_class_name UNIQUE (class_id, name),
    CONSTRAINT chk_methods_loc_non_negative CHECK (loc >= 0),
    CONSTRAINT chk_methods_complexity_non_negative CHECK (complexity >= 0)
);

CREATE TABLE IF NOT EXISTS dependencies (
    id BIGSERIAL PRIMARY KEY,
    source_class BIGINT NOT NULL,
    target_class BIGINT NOT NULL,
    dependency_type VARCHAR(100) NOT NULL,
    CONSTRAINT fk_dependencies_source_class
        FOREIGN KEY (source_class)
        REFERENCES classes (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_dependencies_target_class
        FOREIGN KEY (target_class)
        REFERENCES classes (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_dependencies_edge UNIQUE (source_class, target_class, dependency_type),
    CONSTRAINT chk_dependencies_no_self_reference CHECK (source_class <> target_class)
);

CREATE TABLE IF NOT EXISTS repository_metrics (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT,
    scan_run_id BIGINT,
    date DATE NOT NULL,
    file_count INTEGER NOT NULL DEFAULT 0,
    class_count INTEGER NOT NULL DEFAULT 0,
    method_count INTEGER NOT NULL DEFAULT 0,
    dependency_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_repository_metrics_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_repository_metrics_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE SET NULL (branch_id),
    CONSTRAINT fk_repository_metrics_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE SET NULL,
    CONSTRAINT uq_repository_metrics_repository_branch_date UNIQUE NULLS NOT DISTINCT (repository_id, branch_id, date),
    CONSTRAINT chk_repository_metrics_file_count_non_negative CHECK (file_count >= 0),
    CONSTRAINT chk_repository_metrics_class_count_non_negative CHECK (class_count >= 0),
    CONSTRAINT chk_repository_metrics_method_count_non_negative CHECK (method_count >= 0),
    CONSTRAINT chk_repository_metrics_dependency_count_non_negative CHECK (dependency_count >= 0)
);

CREATE TABLE IF NOT EXISTS risk_scores (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT,
    scan_run_id BIGINT,
    module_name VARCHAR(255) NOT NULL,
    score NUMERIC(5, 2) NOT NULL,
    reason TEXT,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_risk_scores_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_risk_scores_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE SET NULL (branch_id),
    CONSTRAINT fk_risk_scores_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE SET NULL,
    CONSTRAINT chk_risk_scores_score_range CHECK (score >= 0 AND score <= 100)
);

CREATE TABLE IF NOT EXISTS technical_debt (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT,
    scan_run_id BIGINT,
    module_name VARCHAR(255) NOT NULL,
    type VARCHAR(100) NOT NULL,
    severity VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_technical_debt_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_technical_debt_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE SET NULL (branch_id),
    CONSTRAINT fk_technical_debt_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_branches_repository_id
    ON branches (repository_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_branches_one_default_per_repository
    ON branches (repository_id)
    WHERE is_default;

CREATE INDEX IF NOT EXISTS idx_branches_last_seen_commit_sha
    ON branches (last_seen_commit_sha);

CREATE INDEX IF NOT EXISTS idx_commits_repository_id
    ON commits (repository_id);

CREATE INDEX IF NOT EXISTS idx_commits_committed_at
    ON commits (committed_at);

CREATE INDEX IF NOT EXISTS idx_branch_commits_branch_id
    ON branch_commits (branch_id);

CREATE INDEX IF NOT EXISTS idx_branch_commits_commit_id
    ON branch_commits (commit_id);

CREATE INDEX IF NOT EXISTS idx_scan_runs_repository_branch
    ON scan_runs (repository_id, branch_id);

CREATE INDEX IF NOT EXISTS idx_scan_runs_status
    ON scan_runs (status);

CREATE INDEX IF NOT EXISTS idx_scan_runs_started_at
    ON scan_runs (started_at);

CREATE INDEX IF NOT EXISTS idx_file_changes_scan_run_id
    ON file_changes (scan_run_id);

CREATE INDEX IF NOT EXISTS idx_file_changes_change_type
    ON file_changes (change_type);

CREATE INDEX IF NOT EXISTS idx_analysis_artifacts_repository_id
    ON analysis_artifacts (repository_id);

CREATE INDEX IF NOT EXISTS idx_analysis_artifacts_scan_run_id
    ON analysis_artifacts (scan_run_id);

CREATE INDEX IF NOT EXISTS idx_analysis_artifacts_artifact_type
    ON analysis_artifacts (artifact_type);

CREATE INDEX IF NOT EXISTS idx_source_files_repository_id
    ON source_files (repository_id);

CREATE INDEX IF NOT EXISTS idx_source_files_branch_id
    ON source_files (branch_id);

CREATE INDEX IF NOT EXISTS idx_source_files_scan_run_id
    ON source_files (scan_run_id);

CREATE INDEX IF NOT EXISTS idx_classes_file_id
    ON classes (file_id);

CREATE INDEX IF NOT EXISTS idx_methods_class_id
    ON methods (class_id);

CREATE INDEX IF NOT EXISTS idx_dependencies_source_class
    ON dependencies (source_class);

CREATE INDEX IF NOT EXISTS idx_dependencies_target_class
    ON dependencies (target_class);

CREATE INDEX IF NOT EXISTS idx_repository_metrics_repository_id
    ON repository_metrics (repository_id);

CREATE INDEX IF NOT EXISTS idx_repository_metrics_branch_id
    ON repository_metrics (branch_id);

CREATE INDEX IF NOT EXISTS idx_risk_scores_repository_id
    ON risk_scores (repository_id);

CREATE INDEX IF NOT EXISTS idx_risk_scores_scan_run_id
    ON risk_scores (scan_run_id);

CREATE INDEX IF NOT EXISTS idx_risk_scores_module_name
    ON risk_scores (module_name);

CREATE INDEX IF NOT EXISTS idx_technical_debt_repository_id
    ON technical_debt (repository_id);

CREATE INDEX IF NOT EXISTS idx_technical_debt_scan_run_id
    ON technical_debt (scan_run_id);

CREATE INDEX IF NOT EXISTS idx_technical_debt_module_name
    ON technical_debt (module_name);
