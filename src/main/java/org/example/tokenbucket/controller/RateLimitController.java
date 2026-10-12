package org.example.tokenbucket.controller;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.example.tokenbucket.exception.TooManyRequestsException;
import org.example.tokenbucket.service.AsyncWorker;
import org.example.tokenbucket.service.TargetServiceClient;
import org.example.tokenbucket.service.TokenBucketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class RateLimitController {

    private final TargetServiceClient targetServiceClient;
    private final AsyncWorker asyncWorker;
    private final TokenBucketService bucketService;

    @PostMapping("/sync")
    public ResponseEntity<SyncResponse> callSync(@RequestBody(required = false) SyncRequest request) {
        String payload = request != null && request.getPayload() != null ? request.getPayload() : "sync";
        try {
            String result = targetServiceClient.callSync(payload);
            return ResponseEntity.ok(new SyncResponse("ok", result));
        } catch (TooManyRequestsException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new SyncResponse("rate_limited", null, e.getMessage()));
        }
    }

    @PostMapping("/async")
    public ResponseEntity<AsyncResponse> submitAsync(@RequestBody AsyncRequest request) {
        String endpoint = request.getEndpoint() != null ? request.getEndpoint() : "/target/async";
        String payload = request.getPayload() != null ? request.getPayload() : "async";
        long id = asyncWorker.insertTask(endpoint, payload);
        return ResponseEntity.accepted()
                .body(new AsyncResponse("accepted", id, endpoint));
    }

    @GetMapping("/bucket")
    public BucketStatus bucketStatus() {
        BucketStatus status = new BucketStatus();
        status.setTokens(bucketService.getCurrentTokens());
        status.setSyncGranted(bucketService.getSyncTokensGranted());
        status.setSyncDenied(bucketService.getSyncTokensDenied());
        status.setAsyncGranted(bucketService.getAsyncTokensGranted());
        status.setAsyncDenied(bucketService.getAsyncTokensDenied());
        return status;
    }

    @Data
    @NoArgsConstructor
    public static class SyncRequest {
        private String payload;
    }

    @Data
    @NoArgsConstructor
    public static class SyncResponse {
        private String status;
        private String result;
        private String error;

        public SyncResponse(String status, String result) {
            this.status = status;
            this.result = result;
        }

        public SyncResponse(String status, String result, String error) {
            this.status = status;
            this.result = result;
            this.error = error;
        }
    }

    @Data
    @NoArgsConstructor
    public static class AsyncRequest {
        private String endpoint;
        private String payload;
    }

    @Data
    @NoArgsConstructor
    public static class AsyncResponse {
        private String status;
        private long taskId;
        private String endpoint;

        public AsyncResponse(String status, long taskId, String endpoint) {
            this.status = status;
            this.taskId = taskId;
            this.endpoint = endpoint;
        }
    }

    @Data
    @NoArgsConstructor
    public static class BucketStatus {
        private BigDecimal tokens;
        private long syncGranted;
        private long syncDenied;
        private long asyncGranted;
        private long asyncDenied;
    }
}
