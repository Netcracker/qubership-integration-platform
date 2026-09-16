package org.qubership.integration.platform.maven.plugin.domain.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.function.Failable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class ResourceWriteService {
    private final YAMLMapper yamlMapper;

    public static class ResourceWriteException extends Exception {
        public ResourceWriteException(String message) {
            super(message);
        }
    }

    @Autowired
    public ResourceWriteService(@Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper) {
        this.yamlMapper = yamlMapper;
    }

    public void writeResources(String outputDirectory, String resourcesText) throws IOException {
        Files.createDirectories(Paths.get(outputDirectory));
        Failable.stream(groupByName(splitResources(resourcesText)).entrySet()).forEach(entry -> {
            if (entry.getValue().size() > 1) {
                String message = String.format("Duplicate resource '%s'", entry.getKey());
                throw new ResourceWriteException(message);
            }
            String resourceName = entry.getKey();
            String content = entry.getValue().getFirst();
            writeResource(outputDirectory, resourceName, content);
        });
    }

    public void writeResource(String outputDirectory, String resourceName, String content) throws IOException {
        String name = resourceName + ".yaml";
        File file = new File(outputDirectory, name);
        log.info("Writing resource '{}' to file '{}'", resourceName, file.getAbsolutePath());
        try (FileWriter fileWriter = new FileWriter(file)) {
            fileWriter.write(content);
        }
    }

    public static Collection<String> splitResources(String resourceText) {
        return Stream.of(resourceText.split("(^|\\n)---[\\r\\n]"))
            .filter(text -> !text.isEmpty())
            .map(text -> "---" + System.lineSeparator() + text)
            .toList();
    }

    public Map<String, List<String>> groupByName(Collection<String> resources) {
        return resources.stream().collect(Collectors.groupingBy(content -> {
            try {
                return getResourceName(content);
            } catch (Exception ex) {
                throw new RuntimeException(ex.getMessage(), ex);
            }
        }));
    }

    public String getResourceName(String content) throws JsonProcessingException, ResourceWriteException {
        JsonNode node = yamlMapper.readTree(content);
        String kind = node.path("kind").asText();
        if (kind.isEmpty()) {
            throw new ResourceWriteException("Failed to get resource kind");
        }
        String name = node.path("metadata").path("name").asText();
        if (name.isEmpty()) {
            throw new ResourceWriteException("Failed to get resource name");
        }
        return kind.toLowerCase() + "-" + name;
    }
}
