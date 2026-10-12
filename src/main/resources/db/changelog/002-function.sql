--liquibase formatted sql

--changeset vlad:2 splitStatements:false
CREATE OR REPLACE FUNCTION consume_token(
    p_key               text,
    p_capacity          numeric,
    p_refill_rate       numeric,
    p_min_tokens_after  numeric DEFAULT 0
) RETURNS boolean AS $$
DECLARE
v_tokens  numeric;
    v_last    timestamptz;
    v_now     timestamptz := clock_timestamp();
    v_elapsed numeric;
BEGIN
INSERT INTO rate_limit_bucket(bucket_key, tokens, capacity, refill_rate, last_refill)
VALUES (p_key, p_capacity, p_capacity, p_refill_rate, v_now)
    ON CONFLICT (bucket_key) DO NOTHING;

SELECT tokens, last_refill INTO v_tokens, v_last
FROM rate_limit_bucket
WHERE bucket_key = p_key
    FOR UPDATE;

v_elapsed := EXTRACT(EPOCH FROM (v_now - v_last));
    v_tokens  := LEAST(p_capacity, v_tokens + v_elapsed * p_refill_rate);

    IF v_tokens - 1 >= p_min_tokens_after THEN
UPDATE rate_limit_bucket
SET tokens = v_tokens - 1, last_refill = v_now
WHERE bucket_key = p_key;
RETURN true;
ELSE
UPDATE rate_limit_bucket
SET tokens = v_tokens, last_refill = v_now
WHERE bucket_key = p_key;
RETURN false;
END IF;
END;
$$ LANGUAGE plpgsql;