CREATE TABLE audit_log (
    id              BIGSERIAL    PRIMARY KEY,
    transaction_id  UUID         NOT NULL,
    event           VARCHAR(100) NOT NULL,
    old_value       TEXT,
    new_value       TEXT,
    actor           VARCHAR(255),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_transaction ON audit_log (transaction_id);
