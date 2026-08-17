package com.example.vkbot.service;

import com.example.vkbot.config.VkProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class PdfResourceProvider {

    private final Resource resource;

    public PdfResourceProvider(ResourceLoader resourceLoader, VkProperties properties) {
        this.resource = resourceLoader.getResource(properties.pdfPath());
    }

    @PostConstruct
    void validate() throws IOException {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("PDF resource is not readable: " + resource);
        }

        try (InputStream input = resource.getInputStream()) {
            byte[] signature = input.readNBytes(5);
            if (!"%PDF-".equals(new String(signature, StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Configured file is not a PDF: " + resource);
            }
        }
    }

    public Resource get() {
        return resource;
    }
}
