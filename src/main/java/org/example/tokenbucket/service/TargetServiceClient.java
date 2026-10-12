package org.example.tokenbucket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tokenbucket.config.RateLimitProperties;
import org.example.tokenbucket.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TargetServiceClient {

    private final TokenBucketService bucketService;
    private final RateLimitProperties props;

    public String callSync(String payload) {
        long deadline = System.nanoTime() + props.getSync().getMaxWaitMs() * 1_000_000L;

        while (System.nanoTime() < deadline) {
            if (bucketService.tryConsumeSync()) {
                log.debug("Sync: token acquired, calling target service");
                return doHttpCall(payload);
            }
            try {
                Thread.sleep(props.getSync().getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for token", e);
            }
        }
        log.warn("Sync: timeout waiting for token");
        throw new TooManyRequestsException("rate limit exceeded for sync");
    }

    private String doHttpCall(String payload) {
        log.info("Sync: calling target service with payload={}", payload);
        return "sync-response:" + payload;
    }
}
