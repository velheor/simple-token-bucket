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
    private int capacity = 3;
    private int refillRate = 3;

    private Sync sync = new Sync();
    private Async async = new Async();

    @Getter
    @Setter
    public static class Sync {
        private int maxWaitMs = 2000;
        private int pollIntervalMs = 20;
        private int minTokensAfter = 0;
    }

    @Getter
    @Setter
    public static class Async {
        private int minTokensAfter = 1;
        private int pollDelayMs = 50;
        private int retryDelaySeconds = 2;
    }
}
