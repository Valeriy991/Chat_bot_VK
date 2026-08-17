package com.example.vkbot.service;

import com.example.vkbot.config.VkProperties;
import com.example.vkbot.vk.LongPollServer;
import com.example.vkbot.vk.VkApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class VkLongPollWorker {

    private static final Logger log = LoggerFactory.getLogger(VkLongPollWorker.class);

    private final VkApiClient vkApiClient;
    private final VkMessageHandler messageHandler;
    private final Duration retryDelay;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread workerThread;

    public VkLongPollWorker(
            VkApiClient vkApiClient,
            VkMessageHandler messageHandler,
            VkProperties properties
    ) {
        this.vkApiClient = vkApiClient;
        this.messageHandler = messageHandler;
        this.retryDelay = properties.retryDelay();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        workerThread = Thread.ofVirtual()
                .name("vk-long-poll")
                .start(this::runLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runLoop() {
        log.info("VK Long Poll worker started");

        while (running.get()) {
            try {
                LongPollServer server = vkApiClient.getLongPollServer();
                log.info("Connected to VK Long Poll server");
                consume(server);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("VK Long Poll loop failed; reconnecting", e);
                try {
                    sleepBeforeRetry();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("VK Long Poll worker stopped");
    }

    private void consume(LongPollServer initialServer) throws InterruptedException {
        LongPollServer server = initialServer;

        while (running.get()) {
            JsonNode response;
            try {
                response = vkApiClient.poll(server);
            } catch (Exception e) {
                log.warn("Long Poll request failed; reconnecting to VK", e);
                return;
            }

            if (response.has("failed")) {
                int failed = response.path("failed").asInt();
                if (failed == 1) {
                    String newTs = response.path("ts").asText();
                    if (!newTs.isBlank()) {
                        server = server.withTs(newTs);
                        continue;
                    }
                }

                // failed=2 -> key expired; failed=3 -> information lost.
                // In both cases the safest simple recovery is to request a fresh server/key/ts.
                log.warn("VK Long Poll returned failed={}; reconnecting", failed);
                return;
            }

            String nextTs = response.path("ts").asText();
            JsonNode updates = response.path("updates");

            boolean allProcessed = true;
            if (updates.isArray()) {
                for (JsonNode update : updates) {
                    log.info(
                            "Received VK update type={} eventGroupId={}",
                            update.path("type").asText("unknown"),
                            update.path("group_id").asLong(0)
                    );
                    try {
                        messageHandler.handle(update);
                    } catch (Exception e) {
                        allProcessed = false;
                        log.error("Failed to process VK update; current ts will be retried", e);
                        break;
                    }
                }
            }

            if (allProcessed && !nextTs.isBlank()) {
                server = server.withTs(nextTs);
            } else if (!allProcessed) {
                sleepBeforeRetry();
            }
        }
    }

    private void sleepBeforeRetry() throws InterruptedException {
        long millis = retryDelay == null ? 3_000L : retryDelay.toMillis();
        Thread.sleep(Math.max(millis, 250L));
    }
}
