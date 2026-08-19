package com.example.vkbot.vk;

public class VkApiException extends RuntimeException {

    private final String method;
    private final Integer errorCode;

    public VkApiException(String message) {
        super(message);
        this.method = null;
        this.errorCode = null;
    }

    public VkApiException(String message, Throwable cause) {
        super(message, cause);
        this.method = null;
        this.errorCode = null;
    }

    public VkApiException(String method, int errorCode, String errorMessage) {
        super("VK API error in " + method + ": code=" + errorCode + ", message=" + errorMessage);
        this.method = method;
        this.errorCode = errorCode;
    }

    public boolean isApiError(String expectedMethod, int expectedCode) {
        return expectedMethod.equals(method) && errorCode != null && errorCode == expectedCode;
    }

    public String method() {
        return method;
    }

    public Integer errorCode() {
        return errorCode;
    }
}
