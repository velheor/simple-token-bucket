--liquibase formatted sql

--changeset vlad:3
CREATE TABLE sync_requests (
    id          bigserial PRIMARY KEY,
    payload     text,
    status      text NOT NULL,
    wait_ms     bigint,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_sync_requests_created ON sync_requests (created_at);
