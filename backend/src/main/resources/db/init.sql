CREATE TABLE IF NOT EXISTS repositories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_repositories_url UNIQUE (url)
);

CREATE TABLE IF NOT EXISTS source_files (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    path TEXT NOT NULL,
    language VARCHAR(100),
    loc INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_source_files_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_source_files_repository_path UNIQUE (repository_id, path),
    CONSTRAINT chk_source_files_loc_non_negative CHECK (loc >= 0)
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
    date DATE NOT NULL,
    file_count INTEGER NOT NULL DEFAULT 0,
    class_count INTEGER NOT NULL DEFAULT 0,
    method_count INTEGER NOT NULL DEFAULT 0,
    dependency_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_repository_metrics_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_repository_metrics_repository_date UNIQUE (repository_id, date),
    CONSTRAINT chk_repository_metrics_file_count_non_negative CHECK (file_count >= 0),
    CONSTRAINT chk_repository_metrics_class_count_non_negative CHECK (class_count >= 0),
    CONSTRAINT chk_repository_metrics_method_count_non_negative CHECK (method_count >= 0),
    CONSTRAINT chk_repository_metrics_dependency_count_non_negative CHECK (dependency_count >= 0)
);

CREATE TABLE IF NOT EXISTS risk_scores (
    id BIGSERIAL PRIMARY KEY,
    module_name VARCHAR(255) NOT NULL,
    score NUMERIC(5, 2) NOT NULL,
    reason TEXT,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_risk_scores_score_range CHECK (score >= 0 AND score <= 100)
);

CREATE TABLE IF NOT EXISTS technical_debt (
    id BIGSERIAL PRIMARY KEY,
    module_name VARCHAR(255) NOT NULL,
    type VARCHAR(100) NOT NULL,
    severity VARCHAR(50) NOT NULL,
    description TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_source_files_repository_id
    ON source_files (repository_id);

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

CREATE INDEX IF NOT EXISTS idx_risk_scores_module_name
    ON risk_scores (module_name);

CREATE INDEX IF NOT EXISTS idx_technical_debt_module_name
    ON technical_debt (module_name);
