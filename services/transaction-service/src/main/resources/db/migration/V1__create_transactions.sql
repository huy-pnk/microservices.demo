CREATE TABLE transactions (
    id               UUID         PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    merchant_id      VARCHAR(255) NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    from_currency    CHAR(3)      NOT NULL,
    to_currency      CHAR(3)      NOT NULL,
    locked_rate      NUMERIC(19,8),
    card_network     VARCHAR(50),
    country          CHAR(2),
    status           VARCHAR(50)  NOT NULL DEFAULT 'RECEIVED',
    fraud_verdict    VARCHAR(20),
    fraud_reason     TEXT,
    bank_reference   VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_transactions_merchant_created ON transactions (merchant_id, created_at DESC);
CREATE INDEX idx_transactions_status_active ON transactions (status)
    WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'REVERSED');
