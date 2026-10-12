package org.example.tokenbucket.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTask {

    public enum Status {
        NEW("new"),
        PROCESSING("processing"),
        DONE("done");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private Long id;
    private String endpoint;
    private String payload;
    private Status status;
    private int attempts;
    private String lockedBy;
    private OffsetDateTime lockedAt;
    private OffsetDateTime nextAttemptAt;
    private OffsetDateTime createdAt;
}
