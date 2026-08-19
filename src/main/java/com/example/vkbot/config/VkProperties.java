package com.example.vkbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "vk")
public record VkProperties(
        String token,
        long groupId,
        String apiVersion,
        String triggerWord,
        String replyText,
        String alreadySentText,
        String nonMemberText,
        String messagesDisabledCommentText,
        String afterSubscriptionText,
        String deliveryUnavailableText,
        String pdfPath,
        String pdfAttachment,
        long uploadPeerId,
        Duration retryDelay,
        Duration repeatReplyCooldown
) {
}
