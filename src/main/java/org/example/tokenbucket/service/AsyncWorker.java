package org.example.tokenbucket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tokenbucket.config.RateLimitProperties;
import org.example.tokenbucket.entity.AsyncTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncWorker {

    private final JdbcTemplate jdbc;
    private final RateLimitProperties props;
    private final TokenBucketService bucketService;
    private final String workerId = UUID.randomUUID().toString();

    private static final RowMapper<AsyncTask> ROW_MAPPER = (rs, rowNum) -> {
        AsyncTask task = new AsyncTask();
        task.setId(rs.getLong("id"));
        task.setEndpoint(rs.getString("endpoint"));
        task.setPayload(rs.getString("payload"));
        task.setStatus(AsyncTask.Status.valueOf(rs.getString("status").toUpperCase()));
        task.setAttempts(rs.getInt("attempts"));
        task.setLockedBy(rs.getString("locked_by"));
        task.setLockedAt(rs.getObject("locked_at", OffsetDateTime.class));
        task.setNextAttemptAt(rs.getObject("next_attempt_at", OffsetDateTime.class));
        task.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        return task;
    };

    @Scheduled(fixedDelayString = "#{@rateLimitProperties.async.pollDelayMs}")
    public void poll() {
        AsyncTask task = claimNextTask();
        if (task == null) {
            return;
        }

        log.debug("Async worker {}: claimed task {}", workerId, task.getId());

        if (!bucketService.tryConsumeAsync()) {
            log.debug("Async: no token available, deferring task {} by {} ms",
                    task.getId(), props.getAsync().getRetryDelaySeconds());
            jdbc.update("""
                    UPDATE async_tasks
                    SET status = 'new',
                        next_attempt_at = now() + make_interval(secs => ?),
                        locked_by = NULL,
                        locked_at = NULL
                    WHERE id = ?
                    """, props.getAsync().getRetryDelaySeconds(), task.getId());
            return;
        }

        log.debug("Async: token acquired for task {}", task.getId());
        try {
            String result = callTarget(task);
            jdbc.update("UPDATE async_tasks SET status = 'done' WHERE id = ?", task.getId());
            log.info("Async: task {} completed, result={}", task.getId(), result);
        } catch (Exception e) {
            log.error("Async: task {} failed, scheduling retry (attempt={})", task.getId(), task.getAttempts(), e);
            long backoffMs = (long) (1000 * Math.pow(2, task.getAttempts()));
            jdbc.update("""
                    UPDATE async_tasks
                    SET status = 'new',
                        next_attempt_at = now() + make_interval(secs => ?),
                        locked_by = NULL,
                        locked_at = NULL
                    WHERE id = ?
                    """, backoffMs / 1000, task.getId());
        }
    }

    private String callTarget(AsyncTask task) {
        log.info("Async: calling target service endpoint={} payload={}", task.getEndpoint(), task.getPayload());
        return "async-response:" + task.getPayload();
    }

    private AsyncTask claimNextTask() {
        List<AsyncTask> rows = jdbc.query("""
                WITH t AS (
                    SELECT id FROM async_tasks
                    WHERE status = 'new' AND next_attempt_at <= now()
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE async_tasks o
                SET status = 'processing', locked_by = ?, locked_at = now(), attempts = attempts + 1
                FROM t WHERE o.id = t.id
                RETURNING o.*
                """, ROW_MAPPER, workerId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public long insertTask(String endpoint, String payload) {
        return jdbc.queryForObject(
                "INSERT INTO async_tasks (endpoint, payload, status, next_attempt_at) VALUES (?, ?, 'new', now()) RETURNING id",
                Long.class,
                endpoint, payload
        );
    }
}
