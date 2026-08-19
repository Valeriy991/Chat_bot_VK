package com.example.vkbot.vk;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VkApiClientTest {

    @Test
    void shouldReplaceOnlyProblematicVkUploadHost() {
        URI result = VkApiClient.normalizeUploadUri(
                "https://pu.vk.com/c123/upload.php?act=do_add&hash=secret"
        );

        assertEquals("pu.vk.ru", result.getHost());
        assertEquals("/c123/upload.php", result.getPath());
        assertEquals("act=do_add&hash=secret", result.getRawQuery());
    }

    @Test
    void shouldLeaveOtherUploadHostsUntouched() {
        String uploadUrl = "https://example.vk-cdn.test/upload?token=secret";

        assertEquals(URI.create(uploadUrl), VkApiClient.normalizeUploadUri(uploadUrl));
    }

    @Test
    void shouldUseCommunityIdForWallDocumentUpload() {
        assertEquals("235381622", VkApiClient.wallUploadGroupId(235_381_622L));
    }

    @Test
    void shouldUseConfiguredInt32UserForMessagesDocumentUpload() {
        assertEquals("11764588", VkApiClient.messagesUploadPeerId(11_764_588L));
    }
}
