ALTER TABLE transactions
    ALTER COLUMN from_currency TYPE VARCHAR(3),
    ALTER COLUMN to_currency   TYPE VARCHAR(3),
    ALTER COLUMN country       TYPE VARCHAR(2);

ALTER TABLE routing_rules
    ALTER COLUMN country       TYPE VARCHAR(2),
    ALTER COLUMN from_currency TYPE VARCHAR(3);
