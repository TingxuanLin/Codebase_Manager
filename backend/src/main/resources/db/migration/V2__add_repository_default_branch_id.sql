ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS default_branch_id BIGINT;

UPDATE repositories r
SET default_branch_id = b.id
FROM branches b
WHERE r.id = b.repository_id
  AND b.is_default = TRUE
  AND r.default_branch_id IS NULL;

UPDATE repositories r
SET default_branch_id = (
    SELECT b.id
    FROM branches b
    WHERE b.repository_id = r.id
    ORDER BY b.updated_at DESC, b.id DESC
    LIMIT 1
)
WHERE r.default_branch_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_repositories_default_branch_id
    ON repositories (default_branch_id);

UPDATE branches b
SET is_default = (b.id = r.default_branch_id)
FROM repositories r
WHERE b.repository_id = r.id;
