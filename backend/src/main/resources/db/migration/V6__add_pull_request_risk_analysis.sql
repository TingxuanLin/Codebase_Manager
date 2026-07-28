CREATE TABLE IF NOT EXISTS pull_request_risk_analyses (
    id BIGSERIAL PRIMARY KEY,
    pull_request_id BIGINT NOT NULL,
    score INTEGER NOT NULL,
    severity VARCHAR(50) NOT NULL,
    analyzer_version VARCHAR(50) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_pull_request_risk_analyses_pull_request
        FOREIGN KEY (pull_request_id)
        REFERENCES pull_requests (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_pull_request_risk_analyses_pull_request UNIQUE (pull_request_id),
    CONSTRAINT chk_pull_request_risk_analyses_score_range CHECK (score >= 0 AND score <= 100),
    CONSTRAINT chk_pull_request_risk_analyses_severity CHECK (severity IN ('low', 'medium', 'high', 'critical'))
);

CREATE TABLE IF NOT EXISTS pull_request_risk_findings (
    id BIGSERIAL PRIMARY KEY,
    analysis_id BIGINT NOT NULL,
    category VARCHAR(100) NOT NULL,
    severity VARCHAR(50) NOT NULL,
    points INTEGER NOT NULL,
    path TEXT,
    reason TEXT NOT NULL,
    evidence TEXT,
    CONSTRAINT fk_pull_request_risk_findings_analysis
        FOREIGN KEY (analysis_id)
        REFERENCES pull_request_risk_analyses (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_pull_request_risk_findings_points_non_negative CHECK (points >= 0),
    CONSTRAINT chk_pull_request_risk_findings_severity CHECK (severity IN ('low', 'medium', 'high', 'critical')),
    CONSTRAINT chk_pull_request_risk_findings_category_not_blank CHECK (length(trim(category)) > 0),
    CONSTRAINT chk_pull_request_risk_findings_reason_not_blank CHECK (length(trim(reason)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_pull_request_risk_analyses_pull_request
    ON pull_request_risk_analyses (pull_request_id);

CREATE INDEX IF NOT EXISTS idx_pull_request_risk_findings_analysis
    ON pull_request_risk_findings (analysis_id);

CREATE INDEX IF NOT EXISTS idx_pull_request_risk_findings_category
    ON pull_request_risk_findings (category);
