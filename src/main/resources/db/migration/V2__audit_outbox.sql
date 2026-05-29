-- V2: Outbox, idempotency, audit, rule versioning.

CREATE TABLE outbox (
    id            uuid        PRIMARY KEY,
    aggregate_id  uuid        NOT NULL,
    event_type    text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    processed_at  timestamptz,
    retry_count   integer     NOT NULL DEFAULT 0
);

CREATE INDEX ix_outbox_pending ON outbox (created_at) WHERE processed_at IS NULL;

CREATE TABLE processed_events (
    event_id     uuid        NOT NULL,
    consumer_id  text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_id)
);

CREATE TABLE rule_versions (
    version    integer     PRIMARY KEY,
    yaml_hash  text        NOT NULL,
    loaded_at  timestamptz NOT NULL DEFAULT now(),
    loaded_by  text        NOT NULL
);

CREATE TABLE audit_log (
    id            bigserial   PRIMARY KEY,
    actor         text        NOT NULL,
    action        text        NOT NULL,
    resource_id   text        NOT NULL,
    payload_hash  text        NOT NULL,
    occurred_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_actor_time ON audit_log (actor, occurred_at DESC);
CREATE INDEX ix_audit_action     ON audit_log (action, occurred_at DESC);
