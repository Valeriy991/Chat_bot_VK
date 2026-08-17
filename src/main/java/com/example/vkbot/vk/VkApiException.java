package com.example.vkbot.vk;

public class VkApiException extends RuntimeException {

    public VkApiException(String message) {
        super(message);
    }

    public VkApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
