package com.example.vkbot.service;

import com.example.vkbot.config.VkProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerMatcherTest {

    private final TriggerMatcher matcher = new TriggerMatcher(
            new VkProperties(
                    "token",
                    1L,
                    "5.199",
                    "сила",
                    "reply",
                    "already sent",
                    "subscribe first",
                    "enable messages",
                    "after subscription",
                    "delivery unavailable",
                    "classpath:files/Где_мои_силы_—_Анастасия_Гулина_психолог_КПТ.pdf",
                    "",
                    "",
                    0L,
                    3,
                    Duration.ofHours(1),
                    Duration.ofSeconds(3),
                    Duration.ofMinutes(1)
            )
    );

    @Test
    void shouldMatchIgnoringCaseAndOuterSpaces() {
        assertThat(matcher.matches("  СИЛА  ")).isTrue();
        assertThat(matcher.matches("сила")).isTrue();
        assertThat(matcher.matches("СиЛа")).isTrue();
        assertThat(matcher.matches("\u00a0СИЛА\u00a0")).isTrue();
    }

    @Test
    void shouldRequireExactTextAfterNormalization() {
        assertThat(matcher.matches("в силе")).isFalse();
        assertThat(matcher.matches("сила пожалуйста")).isFalse();
        assertThat(matcher.matches("сила! ")).isFalse();
        assertThat(matcher.matches("")).isFalse();
        assertThat(matcher.matches(null)).isFalse();
    }
}
