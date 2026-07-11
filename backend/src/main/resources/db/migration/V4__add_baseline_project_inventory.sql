CREATE TABLE IF NOT EXISTS project_directories (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    scan_run_id BIGINT,
    path TEXT NOT NULL,
    parent_path TEXT,
    depth INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_project_directories_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_project_directories_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_project_directories_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE SET NULL,
    CONSTRAINT uq_project_directories_repository_branch_path UNIQUE (repository_id, branch_id, path),
    CONSTRAINT chk_project_directories_depth_non_negative CHECK (depth >= 0),
    CONSTRAINT chk_project_directories_path_not_blank CHECK (length(trim(path)) > 0)
);

CREATE TABLE IF NOT EXISTS api_routes (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    scan_run_id BIGINT,
    file_id BIGINT,
    class_id BIGINT,
    method_id BIGINT,
    http_method VARCHAR(20) NOT NULL,
    route_path TEXT NOT NULL,
    handler_class VARCHAR(255),
    handler_method VARCHAR(255),
    line_number INTEGER,
    CONSTRAINT fk_api_routes_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_api_routes_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_api_routes_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_api_routes_file
        FOREIGN KEY (file_id)
        REFERENCES source_files (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_api_routes_class
        FOREIGN KEY (class_id)
        REFERENCES classes (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_api_routes_method
        FOREIGN KEY (method_id)
        REFERENCES methods (id)
        ON DELETE SET NULL,
    CONSTRAINT uq_api_routes_repository_branch_method_path UNIQUE (repository_id, branch_id, http_method, route_path, file_id),
    CONSTRAINT chk_api_routes_route_path_not_blank CHECK (length(trim(route_path)) > 0)
);

CREATE TABLE IF NOT EXISTS external_dependencies (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    scan_run_id BIGINT,
    manifest_path TEXT NOT NULL,
    manager VARCHAR(100) NOT NULL,
    package_name TEXT NOT NULL,
    version_spec TEXT,
    dependency_scope VARCHAR(100),
    CONSTRAINT fk_external_dependencies_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_external_dependencies_branch
        FOREIGN KEY (repository_id, branch_id)
        REFERENCES branches (repository_id, id)
        ON DELETE CASCADE,
    CONSTRAINT fk_external_dependencies_scan_run
        FOREIGN KEY (scan_run_id)
        REFERENCES scan_runs (id)
        ON DELETE SET NULL,
    CONSTRAINT uq_external_dependencies_branch_package UNIQUE (repository_id, branch_id, manifest_path, manager, package_name, dependency_scope),
    CONSTRAINT chk_external_dependencies_manifest_path_not_blank CHECK (length(trim(manifest_path)) > 0),
    CONSTRAINT chk_external_dependencies_package_name_not_blank CHECK (length(trim(package_name)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_project_directories_repository_branch
    ON project_directories (repository_id, branch_id);

CREATE INDEX IF NOT EXISTS idx_api_routes_repository_branch
    ON api_routes (repository_id, branch_id);

CREATE INDEX IF NOT EXISTS idx_api_routes_scan_run_id
    ON api_routes (scan_run_id);

CREATE INDEX IF NOT EXISTS idx_external_dependencies_repository_branch
    ON external_dependencies (repository_id, branch_id);

CREATE INDEX IF NOT EXISTS idx_external_dependencies_scan_run_id
    ON external_dependencies (scan_run_id);
