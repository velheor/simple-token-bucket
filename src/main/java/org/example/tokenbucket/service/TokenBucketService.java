package org.example.tokenbucket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tokenbucket.config.RateLimitProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBucketService {

    private static final String CONSUME_SQL = "SELECT consume_token(?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final RateLimitProperties props;
    private final AtomicLong syncTokensGranted = new AtomicLong();
    private final AtomicLong syncTokensDenied = new AtomicLong();
    private final AtomicLong asyncTokensGranted = new AtomicLong();
    private final AtomicLong asyncTokensDenied = new AtomicLong();

    public boolean tryConsumeSync() {
        Boolean ok = jdbc.queryForObject(
                CONSUME_SQL,
                Boolean.class,
                props.getBucketKey(),
                props.getCapacity(),
                props.getRefillRate(),
                props.getSync().getMinTokensAfter()
        );
        if (Boolean.TRUE.equals(ok)) {
            syncTokensGranted.incrementAndGet();
            return true;
        }
        syncTokensDenied.incrementAndGet();
        return false;
    }

    public boolean tryConsumeAsync() {
        Boolean ok = jdbc.queryForObject(
                CONSUME_SQL,
                Boolean.class,
                props.getBucketKey(),
                props.getCapacity(),
                props.getRefillRate(),
                props.getAsync().getMinTokensAfter()
        );
        if (Boolean.TRUE.equals(ok)) {
            asyncTokensGranted.incrementAndGet();
            return true;
        }
        asyncTokensDenied.incrementAndGet();
        return false;
    }

    public BigDecimal getCurrentTokens() {
        return jdbc.queryForObject(
                "SELECT tokens FROM rate_limit_bucket WHERE bucket_key = ?",
                BigDecimal.class,
                props.getBucketKey()
        );
    }

    public long getSyncTokensGranted() {
        return syncTokensGranted.get();
    }

    public long getSyncTokensDenied() {
        return syncTokensDenied.get();
    }

    public long getAsyncTokensGranted() {
        return asyncTokensGranted.get();
    }

    public long getAsyncTokensDenied() {
        return asyncTokensDenied.get();
    }
}
