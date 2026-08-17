package com.example.vkbot.vk;

public record LongPollServer(
        String key,
        String server,
        String ts
) {
    public LongPollServer withTs(String newTs) {
        return new LongPollServer(key, server, newTs);
    }
}
