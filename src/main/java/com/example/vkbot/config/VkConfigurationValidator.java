package com.example.vkbot.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class VkConfigurationValidator {

    private final VkProperties properties;

    public VkConfigurationValidator(VkProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        if (properties.token() == null || properties.token().isBlank()) {
            throw new IllegalStateException("VK_GROUP_TOKEN is required");
        }
        if (properties.groupId() <= 0) {
            throw new IllegalStateException("VK_GROUP_ID must be greater than 0");
        }
        if (properties.triggerWord() == null || properties.triggerWord().isBlank()) {
            throw new IllegalStateException("VK_TRIGGER_WORD must not be blank");
        }
        if (properties.apiVersion() == null || properties.apiVersion().isBlank()) {
            throw new IllegalStateException("VK_API_VERSION must not be blank");
        }
        if (properties.pdfPath() == null || properties.pdfPath().isBlank()) {
            throw new IllegalStateException("VK_PDF_PATH must not be blank");
        }
        if (properties.replyText() == null || properties.replyText().isBlank()) {
            throw new IllegalStateException("VK_REPLY_TEXT must not be blank");
        }
        if (properties.alreadySentText() == null || properties.alreadySentText().isBlank()) {
            throw new IllegalStateException("VK_ALREADY_SENT_TEXT must not be blank");
        }
        if (properties.nonMemberText() == null || properties.nonMemberText().isBlank()) {
            throw new IllegalStateException("VK_NON_MEMBER_TEXT must not be blank");
        }
        if (properties.messagesDisabledCommentText() == null || properties.messagesDisabledCommentText().isBlank()) {
            throw new IllegalStateException("VK_MESSAGES_DISABLED_COMMENT_TEXT must not be blank");
        }
        if (properties.afterSubscriptionText() == null || properties.afterSubscriptionText().isBlank()) {
            throw new IllegalStateException("VK_AFTER_SUBSCRIPTION_TEXT must not be blank");
        }
        if (properties.deliveryUnavailableText() == null || properties.deliveryUnavailableText().isBlank()) {
            throw new IllegalStateException("VK_DELIVERY_UNAVAILABLE_TEXT must not be blank");
        }
        if (properties.pdfPublicUrl() == null || properties.pdfPublicUrl().isBlank()) {
            throw new IllegalStateException("VK_PDF_PUBLIC_URL must not be blank");
        }
        if (properties.uploadPeerId() < 0) {
            throw new IllegalStateException("VK_UPLOAD_PEER_ID must be 0 or a positive long ID");
        }
        if (properties.maxDeliveriesPerWindow() <= 0) {
            throw new IllegalStateException("VK_MAX_DELIVERIES_PER_WINDOW must be greater than 0");
        }
        if (properties.deliveryWindow() == null
                || properties.deliveryWindow().isZero()
                || properties.deliveryWindow().isNegative()) {
            throw new IllegalStateException("VK_DELIVERY_WINDOW must be a positive duration");
        }
        if (properties.repeatReplyCooldown() == null || properties.repeatReplyCooldown().isNegative()) {
            throw new IllegalStateException("VK_REPEAT_REPLY_COOLDOWN must be a non-negative duration");
        }
    }
}
