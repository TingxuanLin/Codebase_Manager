CREATE TABLE IF NOT EXISTS pull_requests (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    github_pr_number INTEGER NOT NULL,
    title TEXT NOT NULL,
    state VARCHAR(50) NOT NULL,
    base_branch VARCHAR(255) NOT NULL,
    head_branch VARCHAR(255) NOT NULL,
    base_sha VARCHAR(64),
    head_sha VARCHAR(64),
    author_login VARCHAR(255),
    html_url TEXT NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_pull_requests_repository
        FOREIGN KEY (repository_id)
        REFERENCES repositories (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_pull_requests_repository_number UNIQUE (repository_id, github_pr_number),
    CONSTRAINT chk_pull_requests_number_positive CHECK (github_pr_number > 0),
    CONSTRAINT chk_pull_requests_title_not_blank CHECK (length(trim(title)) > 0),
    CONSTRAINT chk_pull_requests_base_branch_not_blank CHECK (length(trim(base_branch)) > 0),
    CONSTRAINT chk_pull_requests_head_branch_not_blank CHECK (length(trim(head_branch)) > 0),
    CONSTRAINT chk_pull_requests_html_url_not_blank CHECK (length(trim(html_url)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_pull_requests_repository_id
    ON pull_requests (repository_id);

CREATE INDEX IF NOT EXISTS idx_pull_requests_state
    ON pull_requests (state);

CREATE INDEX IF NOT EXISTS idx_pull_requests_base_branch
    ON pull_requests (base_branch);
