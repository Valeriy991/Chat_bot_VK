package com.example.vkbot.service;

import com.example.vkbot.config.VkProperties;
import com.example.vkbot.vk.VkApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;

@Service
public class VkMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(VkMessageHandler.class);
    private static final int PROCESSED_COMMENT_CACHE_SIZE = 10_000;

    private final TriggerMatcher triggerMatcher;
    private final PdfResourceProvider pdfResourceProvider;
    private final VkApiClient vkApiClient;
    private final VkProperties properties;
    private final Object attachmentLock = new Object();
    private final ConcurrentMap<Long, DeliveryState> deliveryByUser = new ConcurrentHashMap<>();
    private final Set<Long> usersWaitingForSubscription = ConcurrentHashMap.newKeySet();
    private volatile String sharedAttachment;
    private final Map<CommentKey, Boolean> processedComments = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CommentKey, Boolean> eldest) {
                    return size() > PROCESSED_COMMENT_CACHE_SIZE;
                }
            }
    );

    public VkMessageHandler(
            TriggerMatcher triggerMatcher,
            PdfResourceProvider pdfResourceProvider,
            VkApiClient vkApiClient,
            VkProperties properties
    ) {
        this.triggerMatcher = triggerMatcher;
        this.pdfResourceProvider = pdfResourceProvider;
        this.vkApiClient = vkApiClient;
        this.properties = properties;
    }

    public void handle(JsonNode update) {
        String eventType = update.path("type").asText();
        if ("group_join".equals(eventType)) {
            handleGroupJoin(update);
            return;
        }
        if (!"wall_reply_new".equals(eventType)) {
            return;
        }

        JsonNode comment = update.path("object");
        if (!comment.isObject()) {
            log.warn("wall_reply_new without object: {}", update);
            return;
        }

        long userId = comment.path("from_id").asLong();
        long ownerId = comment.path("owner_id").asLong();
        long postId = comment.path("post_id").asLong();
        long commentId = comment.path("id").asLong();
        String text = comment.path("text").asText("");
        boolean triggerMatched = triggerMatcher.matches(text);

        log.info(
                "Received wall comment userId={} ownerId={} postId={} commentId={} triggerMatched={}",
                userId,
                ownerId,
                postId,
                commentId,
                triggerMatched
        );

        // A community has a negative owner_id in wall objects. Ignore comments by communities,
        // deleted users and events that do not belong to this community's wall.
        if (userId <= 0 || ownerId != -properties.groupId() || postId <= 0 || commentId <= 0) {
            log.warn(
                    "Wall comment ignored because its identifiers are invalid or ownerId does not match expectedOwnerId={}",
                    -properties.groupId()
            );
            return;
        }

        if (!triggerMatched) {
            return;
        }

        CommentKey commentKey = new CommentKey(ownerId, postId, commentId);
        if (processedComments.containsKey(commentKey)) {
            return;
        }

        DeliveryState state = deliveryByUser.computeIfAbsent(userId, ignored -> new DeliveryState());
        synchronized (state) {
            // Another handler invocation could have completed while this one was waiting for the user lock.
            if (processedComments.containsKey(commentKey)) {
                return;
            }

            long now = System.nanoTime();
            if (state.fileSent && !state.canSendRepeatReply(now, properties.repeatReplyCooldown().toNanos())) {
                processedComments.put(commentKey, Boolean.TRUE);
                log.debug("Repeat trigger suppressed by cooldown for VK userId={}", userId);
                return;
            }

            log.info("Checking community membership for VK userId={}", userId);
            if (!vkApiClient.isGroupMember(userId)) {
                usersWaitingForSubscription.add(userId);
                if (state.canSendNonMemberReply(now, properties.repeatReplyCooldown().toNanos())) {
                    vkApiClient.sendMessage(
                            userId,
                            properties.nonMemberText(),
                            null,
                            deterministicRandomId(userId, commentKey, "non-member")
                    );
                    state.markNonMemberReplySent(now);
                    log.info("Subscription-required notice sent to VK userId={}", userId);
                } else {
                    log.debug("Subscription-required notice suppressed by cooldown for VK userId={}", userId);
                }
                processedComments.put(commentKey, Boolean.TRUE);
                return;
            }
            log.info("Community membership confirmed for VK userId={}", userId);

            if (!state.fileSent) {
                String attachment = getOrUploadAttachment(userId);
                log.info("Sending presentation message to VK userId={}", userId);
                vkApiClient.sendMessage(
                        userId,
                        properties.replyText(),
                        attachment,
                        deterministicRandomId(userId, commentKey, "file")
                );
                state.fileSent = true;
                usersWaitingForSubscription.remove(userId);
                log.info("Presentation sent to VK userId={} for wall comment {}", userId, commentKey);
            } else {
                vkApiClient.sendMessage(
                        userId,
                        properties.alreadySentText(),
                        null,
                        deterministicRandomId(userId, commentKey, "repeat")
                );
                state.markRepeatReplySent(now);
                log.info("Already-sent notice sent to VK userId={} for wall comment {}", userId, commentKey);
            }

            processedComments.put(commentKey, Boolean.TRUE);
        }
    }

    private void handleGroupJoin(JsonNode update) {
        long eventGroupId = update.path("group_id").asLong();
        long userId = update.path("object").path("user_id").asLong();
        if (eventGroupId != properties.groupId() || userId <= 0) {
            log.warn("group_join ignored because groupId or userId is invalid");
            return;
        }
        if (!usersWaitingForSubscription.contains(userId)) {
            log.debug("group_join ignored because VK userId={} did not request the material", userId);
            return;
        }

        DeliveryState state = deliveryByUser.computeIfAbsent(userId, ignored -> new DeliveryState());
        synchronized (state) {
            if (state.fileSent) {
                usersWaitingForSubscription.remove(userId);
                return;
            }
            if (!vkApiClient.isGroupMember(userId)) {
                log.info("group_join received, but membership is not confirmed yet for VK userId={}", userId);
                return;
            }

            String attachment = getOrUploadAttachment(userId);
            String eventId = update.path("event_id").asText("group_join:" + userId);
            log.info("Sending pending presentation after subscription to VK userId={}", userId);
            vkApiClient.sendMessage(
                    userId,
                    properties.afterSubscriptionText(),
                    attachment,
                    deterministicRandomId(userId, eventId, "joined-file")
            );
            state.fileSent = true;
            usersWaitingForSubscription.remove(userId);
            log.info("Pending presentation sent after subscription to VK userId={}", userId);
        }
    }

    private String getOrUploadAttachment(long userId) {
        String attachment = sharedAttachment;
        if (attachment != null) {
            log.info("Using previously uploaded VK document for userId={}", userId);
            return attachment;
        }

        synchronized (attachmentLock) {
            if (sharedAttachment == null) {
                log.info("Uploading PDF to VK for the first delivery; userId={}", userId);
                sharedAttachment = vkApiClient.uploadDocumentForMessages(userId, pdfResourceProvider.get());
                log.info("PDF successfully uploaded and saved by VK");
            }
            return sharedAttachment;
        }
    }

    private int deterministicRandomId(long userId, Object eventKey, String responseKind) {
        int hash = Objects.hash(properties.groupId(), userId, eventKey, responseKind);
        return hash == Integer.MIN_VALUE ? 0 : Math.abs(hash);
    }

    private record CommentKey(long ownerId, long postId, long commentId) {
    }

    private static final class DeliveryState {
        private boolean fileSent;
        private boolean repeatReplySent;
        private long lastRepeatReplyNanos;
        private boolean nonMemberReplySent;
        private long lastNonMemberReplyNanos;

        private boolean canSendRepeatReply(long now, long cooldownNanos) {
            return !repeatReplySent || now - lastRepeatReplyNanos >= cooldownNanos;
        }

        private void markRepeatReplySent(long now) {
            repeatReplySent = true;
            lastRepeatReplyNanos = now;
        }

        private boolean canSendNonMemberReply(long now, long cooldownNanos) {
            return !nonMemberReplySent || now - lastNonMemberReplyNanos >= cooldownNanos;
        }

        private void markNonMemberReplySent(long now) {
            nonMemberReplySent = true;
            lastNonMemberReplyNanos = now;
        }
    }
}
