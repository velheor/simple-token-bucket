--liquibase formatted sql

--changeset vlad:1
CREATE TABLE rate_limit_bucket (
    bucket_key    text PRIMARY KEY,
    tokens        int NOT NULL,
    capacity      int NOT NULL,
    refill_rate   int NOT NULL,
    last_refill   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE async_tasks (
    id              bigserial PRIMARY KEY,
    endpoint        text NOT NULL,
    payload         text,
    status          text NOT NULL DEFAULT 'new',
    attempts        integer NOT NULL DEFAULT 0,
    locked_by       text,
    locked_at       timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_async_tasks_status_next ON async_tasks (status, next_attempt_at);