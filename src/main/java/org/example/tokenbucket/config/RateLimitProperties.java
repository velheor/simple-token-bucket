package org.example.tokenbucket.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private String bucketKey = "target-service";
    private long capacity = 3;
    private long refillRate = 3;

    private Sync sync = new Sync();
    private Async async = new Async();

    @Getter
    @Setter
    public static class Sync {
        private long maxWaitMs = 2000;
        private long pollIntervalMs = 20;
        private long minTokensAfter = 0;
    }

    @Getter
    @Setter
    public static class Async {
        private long minTokensAfter = 1;
        private long pollDelayMs = 50;
        private long retryDelaySeconds = 2;
    }
}
