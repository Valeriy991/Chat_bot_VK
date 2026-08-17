package com.example.vkbot;

import com.example.vkbot.config.VkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(VkProperties.class)
public class VkTriggerBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(VkTriggerBotApplication.class, args);
    }
}
