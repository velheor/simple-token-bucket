package org.example.tokenbucket.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitBucket {

    private String bucketKey;
    private BigDecimal tokens;
    private BigDecimal capacity;
    private BigDecimal refillRate;
    private OffsetDateTime lastRefill;
}
