--liquibase formatted sql

--changeset vlad:2 splitStatements:false
-- Функция consume_token: пытается снять 1 токен из бакета (token bucket rate limiter).
-- Параметры:
--   p_key               - ключ бакета (например, идентификатор клиента/метода)
--   p_capacity          - максимальная ёмкость бакета (полный запас токенов)
--   p_refill_rate       - скорость пополнения, токенов в секунду
--   p_min_tokens_after  - минимальное количество токенов, которое должно остаться после снятия
-- Возвращает true, если токен успешно снят, иначе false.
CREATE OR REPLACE FUNCTION consume_token(
    p_key text,
    p_capacity int,
    p_refill_rate int,
    p_min_tokens_after int DEFAULT 0
) RETURNS boolean AS
$$
DECLARE
    v_tokens  int; -- текущее число токенов (до/после пополнения)
    v_last    timestamptz; -- время последней перезаписи (refill)
    v_now     timestamptz := clock_timestamp(); -- текущее время (точное, без кэша)
    v_elapsed int; -- прошедшее время в секундах с последнего пополнения
BEGIN
    -- 1. Гарантируем существование бакета: если ключа нет — создаём полный.
    INSERT INTO rate_limit_bucket(bucket_key, tokens, capacity, refill_rate, last_refill)
    VALUES (p_key, p_capacity, p_capacity, p_refill_rate, v_now)
    ON CONFLICT (bucket_key) DO NOTHING;

    -- 2. Блокируем строку бакета (FOR UPDATE), чтобы параллельные вызовы
    --    не снимали токены одновременно.
    SELECT tokens, last_refill
    INTO v_tokens, v_last
    FROM rate_limit_bucket
    WHERE bucket_key = p_key
        FOR UPDATE;

    -- 3. Подсчитываем, сколько токенов добавилось за прошедшее время:
    --    elapsed * refill_rate, но не больше capacity.
    v_elapsed := EXTRACT(EPOCH FROM (v_now - v_last));
    v_tokens := LEAST(p_capacity, v_tokens + v_elapsed * p_refill_rate);

    -- 4. Если после снятия одного токена останется не меньше p_min_tokens_after,
    --    — снимаем и сохраняем; иначе отказываем.
    IF v_tokens - 1 >= p_min_tokens_after THEN
        UPDATE rate_limit_bucket
        SET tokens      = v_tokens - 1,
            last_refill = v_now
        WHERE bucket_key = p_key;
        RETURN true;
    ELSE
        RETURN false;
    END IF;
END;
$$ LANGUAGE plpgsql;
