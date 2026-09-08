package com.deepseek.dsh.llm.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 文件后端 —— model-config.json 的 {activeId, profiles:[...]} 读写（默认实现）。
 */
public final class FileModelProfileBackend implements ModelProfileBackend {

    private static final Logger log = LoggerFactory.getLogger(FileModelProfileBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path configFile;

    public FileModelProfileBackend(Path configFile) {
        this.configFile = configFile;
    }

    @Override
    public JsonNode load() {
        if (!Files.isReadable(configFile)) return null;
        try {
            return MAPPER.readTree(Files.readString(configFile));
        } catch (IOException e) {
            log.warn("Failed to load model profiles from {}: {}", configFile, e.toString());
            return null;
        }
    }

    @Override
    public void persist(JsonNode root) {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            log.warn("Failed to persist model profiles to {}: {}", configFile, e.toString());
        }
    }
}
