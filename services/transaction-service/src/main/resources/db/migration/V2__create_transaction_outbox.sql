CREATE TABLE transaction_outbox (
    id              UUID         PRIMARY KEY,
    transaction_id  UUID         NOT NULL REFERENCES transactions(id),
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    partition_key   VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    retry_count     SMALLINT     NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_pending ON transaction_outbox (status, created_at)
    WHERE status = 'PENDING';
