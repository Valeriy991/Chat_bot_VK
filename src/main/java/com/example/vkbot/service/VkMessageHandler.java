package com.example.vkbot.service;

import com.example.vkbot.config.VkProperties;
import com.example.vkbot.vk.VkApiClient;
import com.example.vkbot.vk.VkApiException;
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
import java.util.regex.Pattern;

@Service
public class VkMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(VkMessageHandler.class);
    private static final int PROCESSED_EVENT_CACHE_SIZE = 10_000;
    private static final Pattern VK_DOCUMENT_ATTACHMENT = Pattern.compile(
            "doc-?\\d+_\\d+(?:_[A-Za-z0-9_-]+)?"
    );

    private final TriggerMatcher triggerMatcher;
    private final PdfResourceProvider pdfResourceProvider;
    private final VkApiClient vkApiClient;
    private final VkProperties properties;
    private final Object attachmentLock = new Object();
    private final ConcurrentMap<Long, DeliveryState> deliveryByUser = new ConcurrentHashMap<>();
    private final Set<Long> usersWaitingForSubscription = ConcurrentHashMap.newKeySet();
    private volatile String sharedAttachment;
    private final Map<TriggerEventKey, Boolean> processedEvents = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TriggerEventKey, Boolean> eldest) {
                    return size() > PROCESSED_EVENT_CACHE_SIZE;
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
        this.sharedAttachment = configuredAttachment(properties.pdfAttachment());
    }

    public void handle(JsonNode update) {
        String eventType = update.path("type").asText();
        if ("group_join".equals(eventType)) {
            handleGroupJoin(update);
            return;
        }
        if ("message_new".equals(eventType)) {
            handleIncomingMessage(update);
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
        handleTrigger(userId, commentKey);
    }

    private void handleIncomingMessage(JsonNode update) {
        long eventGroupId = update.path("group_id").asLong();
        JsonNode message = update.path("object").path("message");
        long userId = message.path("from_id").asLong();
        long peerId = message.path("peer_id").asLong();
        long messageId = message.path("conversation_message_id").asLong(message.path("id").asLong());
        String eventId = update.path("event_id").asText("message:" + userId + ":" + messageId);
        boolean triggerMatched = triggerMatcher.matches(message.path("text").asText(""));

        log.info(
                "Received direct message userId={} peerId={} messageId={} triggerMatched={}",
                userId,
                peerId,
                messageId,
                triggerMatched
        );

        // Only private incoming messages for this community are handled. Group chats are ignored.
        if ((eventGroupId != 0 && eventGroupId != properties.groupId())
                || userId <= 0
                || peerId != userId
                || !triggerMatched) {
            return;
        }

        handleTrigger(userId, new MessageKey(eventId));
    }

    private void handleTrigger(long userId, TriggerEventKey eventKey) {
        if (processedEvents.containsKey(eventKey)) {
            return;
        }

        DeliveryState state = deliveryByUser.computeIfAbsent(userId, ignored -> new DeliveryState());
        synchronized (state) {
            // Another handler invocation could have completed while this one was waiting for the user lock.
            if (processedEvents.containsKey(eventKey)) {
                return;
            }

            long now = System.nanoTime();
            if (state.fileSent && !state.canSendRepeatReply(now, properties.repeatReplyCooldown().toNanos())) {
                processedEvents.put(eventKey, Boolean.TRUE);
                log.debug("Repeat trigger suppressed by cooldown for VK userId={}", userId);
                return;
            }

            log.info("Checking community membership for VK userId={}", userId);
            if (!vkApiClient.isGroupMember(userId)) {
                usersWaitingForSubscription.add(userId);
                if (state.canSendNonMemberReply(now, properties.repeatReplyCooldown().toNanos())) {
                    boolean sent = sendMessageOrHandleDenied(
                            userId,
                            properties.nonMemberText(),
                            null,
                            deterministicRandomId(userId, eventKey, "non-member"),
                            eventKey
                    );
                    if (sent) {
                        state.markNonMemberReplySent(now);
                        log.info("Subscription-required notice sent to VK userId={}", userId);
                    }
                } else {
                    log.debug("Subscription-required notice suppressed by cooldown for VK userId={}", userId);
                }
                processedEvents.put(eventKey, Boolean.TRUE);
                return;
            }
            log.info("Community membership confirmed for VK userId={}", userId);

            if (!state.fileSent) {
                String attachment = getOrUploadAttachmentOrNotify(userId, eventKey);
                if (attachment == null) {
                    processedEvents.put(eventKey, Boolean.TRUE);
                    return;
                }
                log.info("Sending presentation message to VK userId={}", userId);
                boolean sent = sendMessageOrHandleDenied(
                        userId,
                        properties.replyText(),
                        attachment,
                        deterministicRandomId(userId, eventKey, "file"),
                        eventKey
                );
                if (sent) {
                    state.fileSent = true;
                    usersWaitingForSubscription.remove(userId);
                    log.info("Presentation sent to VK userId={} for trigger event {}", userId, eventKey);
                }
            } else {
                boolean sent = sendMessageOrHandleDenied(
                        userId,
                        properties.alreadySentText(),
                        null,
                        deterministicRandomId(userId, eventKey, "repeat"),
                        eventKey
                );
                if (sent) {
                    state.markRepeatReplySent(now);
                    log.info("Already-sent notice sent to VK userId={} for trigger event {}", userId, eventKey);
                }
            }

            processedEvents.put(eventKey, Boolean.TRUE);
        }
    }

    private boolean sendMessageOrHandleDenied(
            long userId,
            String text,
            String attachment,
            int randomId,
            TriggerEventKey eventKey
    ) {
        try {
            vkApiClient.sendMessage(userId, text, attachment, randomId);
            return true;
        } catch (VkApiException e) {
            if (!e.isApiError("messages.send", 901)) {
                throw e;
            }

            log.warn(
                    "VK denied messages.send for userId={} because community messages are not allowed; event will not be retried",
                    userId
            );
            if (eventKey instanceof CommentKey commentKey) {
                replyWithMessagesPermissionInstructions(userId, commentKey);
            }
            return false;
        }
    }

    private void replyWithMessagesPermissionInstructions(long userId, CommentKey commentKey) {
        try {
            vkApiClient.replyToWallComment(
                    commentKey.ownerId(),
                    commentKey.postId(),
                    commentKey.commentId(),
                    properties.messagesDisabledCommentText(),
                    "messages-disabled-" + Integer.toUnsignedString(
                            Objects.hash(properties.groupId(), userId, commentKey)
                    )
            );
            log.info("Messages-permission instructions posted for VK userId={} under comment {}", userId, commentKey);
        } catch (Exception replyFailure) {
            // Failure to post the fallback must not cause the original Long Poll event to loop forever.
            log.warn(
                    "Could not post messages-permission instructions for VK userId={} under comment {}; check wall permission",
                    userId,
                    commentKey,
                    replyFailure
            );
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

            String eventId = update.path("event_id").asText("group_join:" + userId);
            MessageKey eventKey = new MessageKey(eventId);
            String attachment = getOrUploadAttachmentOrNotify(userId, eventKey);
            if (attachment == null) {
                usersWaitingForSubscription.remove(userId);
                return;
            }
            log.info("Sending pending presentation after subscription to VK userId={}", userId);
            try {
                vkApiClient.sendMessage(
                        userId,
                        properties.afterSubscriptionText(),
                        attachment,
                        deterministicRandomId(userId, eventId, "joined-file")
                );
            } catch (VkApiException e) {
                if (!e.isApiError("messages.send", 901)) {
                    throw e;
                }
                log.warn(
                        "Pending file cannot be sent after subscription because VK userId={} has not allowed community messages; event will not be retried",
                        userId
                );
                return;
            }
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
                sharedAttachment = vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get());
                log.info("PDF successfully uploaded and saved by VK");
            }
            return sharedAttachment;
        }
    }

    private String getOrUploadAttachmentOrNotify(long userId, TriggerEventKey eventKey) {
        try {
            return getOrUploadAttachment(userId);
        } catch (IllegalStateException e) {
            notifyPermanentDeliveryFailure(userId, eventKey, e);
            return null;
        } catch (VkApiException e) {
            if (!isPermanentDocumentUploadFailure(e)) {
                throw e;
            }
            notifyPermanentDeliveryFailure(userId, eventKey, e);
            return null;
        }
    }

    private void notifyPermanentDeliveryFailure(long userId, TriggerEventKey eventKey, RuntimeException failure) {
        log.error(
                "PDF delivery cannot continue with the current VK upload configuration; "
                        + "set VK_PDF_ATTACHMENT or a valid VK_UPLOAD_PEER_ID; event will not be retried",
                failure
        );
        sendMessageOrHandleDenied(
                userId,
                properties.deliveryUnavailableText(),
                null,
                deterministicRandomId(userId, eventKey, "delivery-unavailable"),
                eventKey
        );
    }

    private static boolean isPermanentDocumentUploadFailure(VkApiException failure) {
        return failure.isApiError("docs.getMessagesUploadServer", 15)
                || failure.isApiError("docs.getMessagesUploadServer", 27)
                || failure.isApiError("docs.getMessagesUploadServer", 100)
                || failure.isApiError("docs.getMessagesUploadServer", 901)
                || failure.isApiError("docs.getWallUploadServer", 15)
                || failure.isApiError("docs.getWallUploadServer", 27)
                || failure.isApiError("docs.getWallUploadServer", 100)
                || failure.isApiError("docs.save", 15)
                || failure.isApiError("docs.save", 27)
                || failure.isApiError("docs.save", 100)
                || failure.isApiError("docs.save", 105);
    }

    private static String configuredAttachment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String attachment = value.strip();
        if (!VK_DOCUMENT_ATTACHMENT.matcher(attachment).matches()) {
            throw new IllegalArgumentException(
                    "VK_PDF_ATTACHMENT must have format doc{owner_id}_{document_id}[_access_key]"
            );
        }
        return attachment;
    }

    private int deterministicRandomId(long userId, Object eventKey, String responseKind) {
        int hash = Objects.hash(properties.groupId(), userId, eventKey, responseKind);
        return hash == Integer.MIN_VALUE ? 0 : Math.abs(hash);
    }

    private sealed interface TriggerEventKey permits CommentKey, MessageKey {
    }

    private record CommentKey(long ownerId, long postId, long commentId) implements TriggerEventKey {
    }

    private record MessageKey(String eventId) implements TriggerEventKey {
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
