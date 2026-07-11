CREATE TABLE IF NOT EXISTS pull_request_file_changes (
    id BIGSERIAL PRIMARY KEY,
    pull_request_id BIGINT NOT NULL,
    path TEXT NOT NULL,
    old_path TEXT,
    change_type VARCHAR(50) NOT NULL,
    additions INTEGER NOT NULL DEFAULT 0,
    deletions INTEGER NOT NULL DEFAULT 0,
    changes INTEGER NOT NULL DEFAULT 0,
    patch TEXT,
    blob_url TEXT,
    raw_url TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_pull_request_file_changes_pull_request
        FOREIGN KEY (pull_request_id)
        REFERENCES pull_requests (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_pull_request_file_changes_pr_path UNIQUE (pull_request_id, path),
    CONSTRAINT chk_pull_request_file_changes_change_type CHECK (change_type IN ('added', 'modified', 'deleted', 'renamed', 'copied', 'changed', 'unchanged', 'removed')),
    CONSTRAINT chk_pull_request_file_changes_additions_non_negative CHECK (additions >= 0),
    CONSTRAINT chk_pull_request_file_changes_deletions_non_negative CHECK (deletions >= 0),
    CONSTRAINT chk_pull_request_file_changes_changes_non_negative CHECK (changes >= 0),
    CONSTRAINT chk_pull_request_file_changes_path_not_blank CHECK (length(trim(path)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_pull_request_file_changes_pull_request
    ON pull_request_file_changes (pull_request_id);

CREATE INDEX IF NOT EXISTS idx_pull_request_file_changes_change_type
    ON pull_request_file_changes (change_type);

ALTER TABLE pull_requests
    ADD COLUMN IF NOT EXISTS diff_fetched_at TIMESTAMPTZ;
