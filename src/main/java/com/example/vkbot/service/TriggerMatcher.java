package com.example.vkbot.service;

import com.example.vkbot.config.VkProperties;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

@Component
public class TriggerMatcher {

    private final String normalizedTrigger;

    public TriggerMatcher(VkProperties properties) {
        this.normalizedTrigger = normalize(properties.triggerWord());
    }

    public boolean matches(String incomingText) {
        return normalize(incomingText).equals(normalizedTrigger);
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
