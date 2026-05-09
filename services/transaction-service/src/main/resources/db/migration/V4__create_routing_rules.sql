CREATE TABLE routing_rules (
    id               BIGSERIAL    PRIMARY KEY,
    card_network     VARCHAR(50),
    country          CHAR(2),
    from_currency    CHAR(3),
    bank_connector_id VARCHAR(100) NOT NULL,
    priority         INT          NOT NULL DEFAULT 0,
    active           BOOLEAN      NOT NULL DEFAULT true
);

INSERT INTO routing_rules (card_network, country, from_currency, bank_connector_id, priority, active)
VALUES (NULL, NULL, NULL, 'default-connector', 0, true);
