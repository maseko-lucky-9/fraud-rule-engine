-- V4: Partitioning stubs for long-term retention.
--
-- The live `transactions` table is unpartitioned and accrues ~20 GB/year at
-- the design rate. A multi-year retention window will eventually exceed the
-- comfortable single-table footprint for ad-hoc queries and vacuum windows.
--
-- This migration ships the partitioned-parent + 3 monthly children alongside
-- the live table — NOT a swap. The data migration (parent↔child detach/attach
-- + backfill) is a Day-N+1 operational task documented in
-- docs/runbooks/disaster-recovery.md and is OUT OF SCOPE for this PR.
--
-- The stub exists so the production team can:
--  - validate the partition shape against their query mix in a staging run,
--  - extend the child-table set ahead of cut-over,
--  - lift this migration into a swap migration when the data freeze window opens.
--
-- Until the swap, transactions_partitioned has zero rows and is invisible
-- to all application code. Removing it is a single DROP TABLE.

CREATE TABLE transactions_partitioned (
    tx_id            uuid          NOT NULL,
    account_id       text          NOT NULL,
    amount           numeric(19,4) NOT NULL CHECK (amount >= 0),
    currency         char(3)       NOT NULL,
    mcc              text          NOT NULL,
    channel          text          NOT NULL,
    country          char(2)       NOT NULL,
    ip_country       char(2)       NOT NULL,
    device_id        text,
    merchant_id      text,
    account_age_days integer       NOT NULL CHECK (account_age_days >= 0),
    event_ts         timestamptz   NOT NULL,
    payload          jsonb         NOT NULL,
    received_at      timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tx_id, received_at)
) PARTITION BY RANGE (received_at);

-- Three monthly windows around the cut-over. Operators extend per month.
-- Dates are intentionally fixed (not generated) so this migration is
-- deterministic and idempotent across Flyway re-runs.
CREATE TABLE transactions_partitioned_2026_05 PARTITION OF transactions_partitioned
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE transactions_partitioned_2026_06 PARTITION OF transactions_partitioned
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE transactions_partitioned_2026_07 PARTITION OF transactions_partitioned
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

-- Match the live table's read patterns so the stub is realistically
-- benchmarkable. Both indexes are partition-local.
CREATE INDEX ix_txp_account_received ON transactions_partitioned (account_id, received_at DESC);
CREATE INDEX ix_txp_received_at      ON transactions_partitioned (received_at);
