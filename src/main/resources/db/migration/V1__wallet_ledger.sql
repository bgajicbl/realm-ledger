-- Append-only wallet ledger.
--
-- Column types are chosen to satisfy Hibernate's `ddl-auto: validate` as well as MySQL:
-- a String id maps to VARCHAR (a CHAR column fails validation), and Instant maps to
-- DATETIME(6), not TIMESTAMP. TIMESTAMP would also cap the table at 2038 and convert
-- silently against the session timezone, neither of which belongs in a ledger.
--
-- Balance is derived by folding rows, never stored. The unique index on
-- idempotency_key is load-bearing: it is what makes a concurrent retry safe without a
-- distributed lock, so it is a correctness constraint and not an optimisation.

CREATE TABLE wallet_ledger_entry (
    entry_id        VARCHAR(36)  NOT NULL,
    player_id       BIGINT       NOT NULL,
    entry_type      VARCHAR(8)   NOT NULL,
    amount_units    BIGINT       NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    reason          VARCHAR(64)  NOT NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (entry_id),
    CONSTRAINT uq_wallet_ledger_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_wallet_ledger_amount_non_negative CHECK (amount_units >= 0)
) ENGINE = InnoDB;

-- Every balance read is "all rows for this player", so this index is the hot path.
CREATE INDEX ix_wallet_ledger_player ON wallet_ledger_entry (player_id, occurred_at);
