-- V1: Initial schema for fraud rule engine
-- See plan §4a for full rationale.

CREATE TABLE transactions (
    tx_id          uuid          PRIMARY KEY,
    account_id     text          NOT NULL,
    amount         numeric(19,4) NOT NULL CHECK (amount >= 0),
    currency       char(3)       NOT NULL,
    mcc            text          NOT NULL,
    channel        text          NOT NULL,
    country        char(2)       NOT NULL,
    ip_country     char(2)       NOT NULL,
    device_id      text,
    merchant_id    text,
    account_age_days integer     NOT NULL CHECK (account_age_days >= 0),
    event_ts       timestamptz   NOT NULL,
    payload        jsonb         NOT NULL,
    received_at    timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX ix_tx_account_received ON transactions (account_id, received_at DESC);
CREATE INDEX ix_tx_received_at      ON transactions (received_at);

CREATE TABLE decisions (
    decision_id      uuid          PRIMARY KEY,
    tx_id            uuid          NOT NULL REFERENCES transactions(tx_id) ON DELETE CASCADE,
    account_id       text          NOT NULL,
    status           text          NOT NULL CHECK (status IN ('APPROVE','REVIEW','BLOCK')),
    score            numeric(4,3)  NOT NULL CHECK (score >= 0 AND score <= 1),
    rule_set_version integer       NOT NULL,
    evaluated_at     timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX ix_dec_status_eval ON decisions (status, evaluated_at DESC);
CREATE INDEX ix_dec_account     ON decisions (account_id, evaluated_at DESC);
CREATE INDEX ix_dec_tx          ON decisions (tx_id);

CREATE TABLE decision_rules (
    decision_id      uuid    NOT NULL REFERENCES decisions(decision_id) ON DELETE CASCADE,
    rule_id          text    NOT NULL,
    matched_priority integer NOT NULL,
    reason           text    NOT NULL,
    PRIMARY KEY (decision_id, rule_id)
);

CREATE INDEX ix_dr_rule_id ON decision_rules (rule_id);
