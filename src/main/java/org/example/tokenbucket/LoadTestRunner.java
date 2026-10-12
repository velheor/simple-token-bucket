package org.example.tokenbucket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tokenbucket.controller.RateLimitController.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadTestRunner implements CommandLineRunner {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String BASE_URL = "http://localhost:8080/api";
    private static final int SYNC_THREADS = 10;
    private static final int SYNC_REQUESTS_PER_THREAD = 10;
    private static final int ASYNC_REQUESTS = 10;
    private static final long DELAY_MS = 500;

    @Override
    public void run(String... args) {
        log.info("=== Load test starting ===");
        log.info("Firing {} sync threads ({} requests each) and {} async requests",
                SYNC_THREADS, SYNC_REQUESTS_PER_THREAD, ASYNC_REQUESTS);

        Thread[] syncThreads = new Thread[SYNC_THREADS];
        for (int t = 0; t < SYNC_THREADS; t++) {
            int threadId = t;
            syncThreads[t] = new Thread(() -> runSyncRequests(threadId), "sync-test-" + (threadId + 1));
            syncThreads[t].start();
        }

        Thread asyncThread = new Thread(this::runAsyncRequests, "async-test");
        asyncThread.start();

        try {
            for (Thread thread : syncThreads) {
                thread.join();
            }
            asyncThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("=== Waiting 5s for async tasks to complete ===");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        BucketStatus status = restTemplate.getForObject(BASE_URL + "/bucket", BucketStatus.class);
        log.info("=== Final bucket status ===");
        log.info("Tokens: {}", status.getTokens());
        log.info("Sync granted: {}, denied: {}", status.getSyncGranted(), status.getSyncDenied());
        log.info("Async granted: {}, denied: {}", status.getAsyncGranted(), status.getAsyncDenied());
        log.info("=== Load test complete ===");
    }

    private void runSyncRequests(int threadId) {
        long start = System.currentTimeMillis();
        int ok = 0, limited = 0;

        for (int i = 1; i <= SYNC_REQUESTS_PER_THREAD; i++) {
            long t0 = System.currentTimeMillis();
            SyncResponse resp = callSync(threadId * SYNC_REQUESTS_PER_THREAD + i);
            long elapsed = System.currentTimeMillis() - t0;
            if ("ok".equals(resp.getStatus())) {
                ok++;
                log.info("[SYNC T{}-{}] OK in {} ms — result={}", threadId + 1, i, elapsed, resp.getResult());
            } else {
                limited++;
                log.warn("[SYNC T{}-{}] RATE LIMITED after {} ms — {}", threadId + 1, i, elapsed, resp.getError());
            }
            sleep(DELAY_MS);
        }

        long total = System.currentTimeMillis() - start;
        log.info("[SYNC T{} SUMMARY] ok={}, limited={}, total={}ms, avg={}ms",
                threadId + 1, ok, limited, total, total / SYNC_REQUESTS_PER_THREAD);
    }

    private void runAsyncRequests() {
        long start = System.currentTimeMillis();

        for (int i = 1; i <= ASYNC_REQUESTS; i++) {
            long t0 = System.currentTimeMillis();
            AsyncResponse resp = callAsync(i);
            long elapsed = System.currentTimeMillis() - t0;
            log.info("[ASYNC {}] ACCEPTED in {} ms — taskId={}, endpoint={}", i, elapsed, resp.getTaskId(), resp.getEndpoint());
            sleep(DELAY_MS);
        }

        long total = System.currentTimeMillis() - start;
        log.info("[ASYNC SUMMARY] submitted={}, total={}ms, avg={}ms", ASYNC_REQUESTS, total, total / ASYNC_REQUESTS);
    }

    private SyncResponse callSync(int n) {
        SyncRequest req = new SyncRequest();
        req.setPayload("sync-" + n);
        try {
            return restTemplate.postForObject(BASE_URL + "/sync", req, SyncResponse.class);
        } catch (Exception e) {
            SyncResponse resp = new SyncResponse();
            resp.setStatus("error");
            resp.setError(e.getMessage());
            return resp;
        }
    }

    private AsyncResponse callAsync(int n) {
        AsyncRequest req = new AsyncRequest();
        req.setEndpoint("/target/async-" + n);
        req.setPayload("async-" + n);
        try {
            return restTemplate.postForObject(BASE_URL + "/async", req, AsyncResponse.class);
        } catch (Exception e) {
            AsyncResponse resp = new AsyncResponse();
            resp.setStatus("error");
            resp.setEndpoint("/target/async-" + n);
            return resp;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
