package com.example.vkbot.service;

import com.example.vkbot.config.VkProperties;
import com.example.vkbot.vk.VkApiClient;
import com.example.vkbot.vk.VkApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VkMessageHandlerTest {

    private static final long GROUP_ID = 123L;
    private static final long USER_ID = 456L;
    private static final long LARGE_USER_ID = 200_002_574_488L;

    @Mock
    private PdfResourceProvider pdfResourceProvider;

    @Mock
    private VkApiClient vkApiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private VkMessageHandler handler;

    @BeforeEach
    void setUp() {
        VkProperties properties = testProperties("");
        handler = new VkMessageHandler(
                new TriggerMatcher(properties),
                pdfResourceProvider,
                vkApiClient,
                properties
        );
    }

    @Test
    void shouldSendPdfToCommunityMemberOnCaseInsensitiveCommentTrigger() {
        when(vkApiClient.isGroupMember(USER_ID)).thenReturn(true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenReturn("doc-123_1_key");

        handler.handle(commentUpdate(10, "  СиЛа  "));

        verify(vkApiClient).isGroupMember(USER_ID);
        verify(vkApiClient).uploadDocumentForMessages(pdfResourceProvider.get());
        verify(vkApiClient).sendMessage(
                eq(USER_ID),
                eq("Вот ваш файл"),
                eq("doc-123_1_key"),
                anyInt()
        );
    }

    @Test
    void shouldPostCommentInstructionsAndConsumeEventWhenMessagesAreNotAllowed() {
        when(vkApiClient.isGroupMember(USER_ID)).thenReturn(true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenReturn("doc-123_1_key");
        doThrow(new VkApiException("messages.send", 901, "Can't send messages for users without permission"))
                .when(vkApiClient)
                .sendMessage(eq(USER_ID), eq("Вот ваш файл"), eq("doc-123_1_key"), anyInt());

        JsonNode update = commentUpdate(10, "сила");
        handler.handle(update);
        handler.handle(update);

        verify(vkApiClient, times(1)).sendMessage(
                eq(USER_ID),
                eq("Вот ваш файл"),
                eq("doc-123_1_key"),
                anyInt()
        );
        verify(vkApiClient, times(1)).replyToWallComment(
                eq(-GROUP_ID),
                eq(7L),
                eq(10L),
                eq("Откройте сообщения сообщества и отправьте слово сила"),
                anyString()
        );
    }

    @Test
    void shouldSendPdfWhenTriggerArrivesInDirectMessage() {
        when(vkApiClient.isGroupMember(USER_ID)).thenReturn(true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenReturn("doc-123_1_key");

        handler.handle(directMessageUpdate(42, "СИЛА"));

        verify(vkApiClient).isGroupMember(USER_ID);
        verify(vkApiClient).sendMessage(
                eq(USER_ID),
                eq("Вот ваш файл"),
                eq("doc-123_1_key"),
                anyInt()
        );
    }

    @Test
    void shouldSendSubscriptionRequiredNoticeOnceDuringCooldown() {
        when(vkApiClient.isGroupMember(USER_ID)).thenReturn(false);

        handler.handle(commentUpdate(10, "сила"));
        handler.handle(commentUpdate(11, "СИЛА"));

        verify(vkApiClient, times(2)).isGroupMember(USER_ID);
        verify(vkApiClient, never()).uploadDocumentForMessages(org.mockito.ArgumentMatchers.any());
        verify(vkApiClient, times(1)).sendMessage(
                eq(USER_ID),
                eq("Сначала подпишитесь"),
                isNull(),
                anyInt()
        );
    }

    @Test
    void shouldAutomaticallySendPdfWhenPendingUserJoinsCommunity() {
        when(vkApiClient.isGroupMember(USER_ID)).thenReturn(false, true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenReturn("doc-123_1_key");

        handler.handle(commentUpdate(10, "сила"));
        handler.handle(groupJoinUpdate(USER_ID));

        verify(vkApiClient, times(2)).isGroupMember(USER_ID);
        verify(vkApiClient).uploadDocumentForMessages(pdfResourceProvider.get());
        verify(vkApiClient).sendMessage(
                eq(USER_ID),
                eq("После подписки — ваш файл"),
                eq("doc-123_1_key"),
                anyInt()
        );
    }

    @Test
    void shouldAutomaticallySendPdfToPendingUserWhoseIdExceedsInt32() {
        when(vkApiClient.isGroupMember(LARGE_USER_ID)).thenReturn(false, true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenReturn("doc-123_1_key");

        handler.handle(commentUpdate(20, LARGE_USER_ID, "сила"));
        handler.handle(groupJoinUpdate(LARGE_USER_ID));

        verify(vkApiClient, times(2)).isGroupMember(LARGE_USER_ID);
        verify(vkApiClient).uploadDocumentForMessages(pdfResourceProvider.get());
        verify(vkApiClient).sendMessage(
                eq(LARGE_USER_ID),
                eq("После подписки — ваш файл"),
                eq("doc-123_1_key"),
                anyInt()
        );
    }

    @Test
    void shouldConsumeJoinAndNotifyUserWhenVkPermanentlyRejectsDocumentUpload() {
        when(vkApiClient.isGroupMember(LARGE_USER_ID)).thenReturn(false, true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenThrow(new VkApiException(
                        "docs.getWallUploadServer",
                        15,
                        "Access denied: User can't upload docs to this group"
                ));

        JsonNode joinUpdate = groupJoinUpdate(LARGE_USER_ID);
        handler.handle(commentUpdate(21, LARGE_USER_ID, "сила"));
        handler.handle(joinUpdate);
        handler.handle(joinUpdate);

        verify(vkApiClient, times(1)).uploadDocumentForMessages(pdfResourceProvider.get());
        verify(vkApiClient).sendMessage(
                eq(LARGE_USER_ID),
                eq("Материал временно недоступен"),
                isNull(),
                anyInt()
        );
    }

    @Test
    void shouldUseConfiguredVkAttachmentWithoutUploadingPdfAgain() {
        VkProperties properties = testProperties("doc-123_99_access-key");
        VkMessageHandler configuredHandler = new VkMessageHandler(
                new TriggerMatcher(properties),
                pdfResourceProvider,
                vkApiClient,
                properties
        );
        when(vkApiClient.isGroupMember(USER_ID)).thenReturn(true);

        configuredHandler.handle(commentUpdate(22, "сила"));

        verify(vkApiClient, never()).uploadDocumentForMessages(org.mockito.ArgumentMatchers.any());
        verify(vkApiClient).sendMessage(
                eq(USER_ID),
                eq("Вот ваш файл"),
                eq("doc-123_99_access-key"),
                anyInt()
        );
    }

    @Test
    void shouldIgnoreJoinWhenUserDidNotRequestMaterial() {
        handler.handle(groupJoinUpdate(USER_ID));

        verify(vkApiClient, never()).isGroupMember(USER_ID);
        verify(vkApiClient, never()).sendMessage(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void shouldSendAlreadySentNoticeOnceDuringCooldown() {
        when(vkApiClient.isGroupMember(USER_ID)).thenReturn(true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenReturn("doc-123_1_key");

        handler.handle(commentUpdate(10, "сила"));
        handler.handle(commentUpdate(11, "СИЛА"));
        handler.handle(commentUpdate(12, "сила"));

        verify(vkApiClient, times(1)).uploadDocumentForMessages(pdfResourceProvider.get());
        verify(vkApiClient).sendMessage(
                eq(USER_ID),
                eq("Вот ваш файл"),
                eq("doc-123_1_key"),
                anyInt()
        );
        verify(vkApiClient).sendMessage(
                eq(USER_ID),
                eq("Файл уже отправлен"),
                isNull(),
                anyInt()
        );
        verify(vkApiClient, times(2)).sendMessage(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(vkApiClient, times(2)).isGroupMember(USER_ID);
    }

    @Test
    void shouldIgnoreReplayOfAlreadyProcessedComment() {
        when(vkApiClient.isGroupMember(USER_ID)).thenReturn(true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenReturn("doc-123_1_key");

        JsonNode update = commentUpdate(10, "сила");
        handler.handle(update);
        handler.handle(update);

        verify(vkApiClient, times(1)).isGroupMember(USER_ID);
        verify(vkApiClient, times(1)).sendMessage(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void shouldReuseUploadedDocumentForDifferentUsers() {
        long secondUserId = 789L;
        when(vkApiClient.isGroupMember(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        when(pdfResourceProvider.get()).thenReturn(new ByteArrayResource("%PDF-test".getBytes()));
        when(vkApiClient.uploadDocumentForMessages(pdfResourceProvider.get()))
                .thenReturn("doc-123_1_key");

        handler.handle(commentUpdate(10, USER_ID, "сила"));
        handler.handle(commentUpdate(11, secondUserId, "сила"));

        verify(vkApiClient, times(1)).uploadDocumentForMessages(pdfResourceProvider.get());
        verify(vkApiClient).sendMessage(eq(USER_ID), eq("Вот ваш файл"), eq("doc-123_1_key"), anyInt());
        verify(vkApiClient).sendMessage(eq(secondUserId), eq("Вот ваш файл"), eq("doc-123_1_key"), anyInt());
    }

    @Test
    void shouldIgnoreMessagesAndCommentsOnAnotherWall() throws Exception {
        handler.handle(objectMapper.readTree("""
                {"type":"message_new","object":{"message":{"from_id":456,"text":"сила"}}}
                """));
        handler.handle(objectMapper.readTree("""
                {"type":"wall_reply_new","object":{"id":10,"from_id":456,"owner_id":-999,"post_id":7,"text":"сила"}}
                """));

        verify(vkApiClient, never()).isGroupMember(org.mockito.ArgumentMatchers.anyLong());
    }

    private JsonNode commentUpdate(long commentId, String text) {
        return commentUpdate(commentId, USER_ID, text);
    }

    private JsonNode commentUpdate(long commentId, long userId, String text) {
        return objectMapper.createObjectNode()
                .put("type", "wall_reply_new")
                .set("object", objectMapper.createObjectNode()
                        .put("id", commentId)
                        .put("from_id", userId)
                        .put("owner_id", -GROUP_ID)
                        .put("post_id", 7)
                        .put("text", text));
    }

    private JsonNode groupJoinUpdate(long userId) {
        return objectMapper.createObjectNode()
                .put("type", "group_join")
                .put("event_id", "join-event-1")
                .put("group_id", GROUP_ID)
                .set("object", objectMapper.createObjectNode()
                        .put("user_id", userId)
                        .put("join_type", "join"));
    }

    private JsonNode directMessageUpdate(long messageId, String text) {
        return objectMapper.createObjectNode()
                .put("type", "message_new")
                .put("event_id", "message-event-" + messageId)
                .put("group_id", GROUP_ID)
                .set("object", objectMapper.createObjectNode()
                        .set("message", objectMapper.createObjectNode()
                                .put("id", messageId)
                                .put("conversation_message_id", messageId)
                                .put("from_id", USER_ID)
                                .put("peer_id", USER_ID)
                                .put("text", text)));
    }

    private VkProperties testProperties(String pdfAttachment) {
        return new VkProperties(
                "token",
                GROUP_ID,
                "5.199",
                "сила",
                "Вот ваш файл",
                "Файл уже отправлен",
                "Сначала подпишитесь",
                "Откройте сообщения сообщества и отправьте слово сила",
                "После подписки — ваш файл",
                "Материал временно недоступен",
                "classpath:files/Где_мои_силы_—_Анастасия_Гулина_психолог_КПТ.pdf",
                pdfAttachment,
                0L,
                Duration.ofSeconds(3),
                Duration.ofMinutes(1)
        );
    }
}
