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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private record ResourceNameAndKind(String name, String kind) {}

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
        Failable.stream(groupByNameAndKind(splitResources(resourcesText)).entrySet()).forEach(entry -> {
            if (entry.getValue().size() > 1) {
                String message = String.format("Duplicate resource '%s'", entry.getKey());
                throw new ResourceWriteException(message);
            }
            ResourceNameAndKind resourceNameAndKind = entry.getKey();
            String content = entry.getValue().getFirst();
            writeResource(outputDirectory, resourceNameAndKind, content);
        });
    }

    private void writeResource(
        String outputDirectory,
        ResourceNameAndKind resourceNameAndKind,
        String content
    ) throws IOException {
        String name = getResourceFileName(resourceNameAndKind);
        File file = new File(outputDirectory, name);
        log.info("Writing resource '{}' of kind '{}' to file '{}'",
            resourceNameAndKind.name, resourceNameAndKind.kind, file.getAbsolutePath());
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    private static String getResourceFileName(ResourceNameAndKind resourceNameAndKind) {
        return String.format("%s-%s.yaml", resourceNameAndKind.kind, resourceNameAndKind.name);
    }

    private static Collection<String> splitResources(String resourceText) {
        return Stream.of(resourceText.split("(^|\\n)---[\\r\\n]"))
            .filter(text -> !text.isEmpty())
            .map(text -> "---\n" + text)
            .toList();
    }

    private Map<ResourceNameAndKind, List<String>> groupByNameAndKind(Collection<String> resources) {
        return resources.stream().collect(Collectors.groupingBy(content -> {
            try {
                return getResourceNameAndKind(content);
            } catch (Exception ex) {
                throw new RuntimeException(ex.getMessage(), ex);
            }
        }));
    }

    private ResourceNameAndKind getResourceNameAndKind(String content) throws JsonProcessingException, ResourceWriteException {
        JsonNode node = yamlMapper.readTree(removeHelmTemplateExpressions(content));
        String kind = node.path("kind").asText();
        if (kind.isEmpty()) {
            throw new ResourceWriteException("Failed to get resource kind");
        }
        String name = node.path("metadata").path("name").asText();
        if (name.isEmpty()) {
            throw new ResourceWriteException("Failed to get resource name");
        }
        return new ResourceNameAndKind(name, kind);
    }

    /**
     * Strips Helm expressions so the document parses. A {@code {{ ... }}} in a value position starts a
     * YAML flow mapping and fails the parse, and only the kind and the name are read here — the file is
     * written from the original content.
     *
     * <p>The group is non-capturing on purpose. Written {@code (:?} it is a capturing group holding an
     * optional colon, which Java compiles to a recursive matcher: an unterminated {@code &#123;&#123;}
     * followed by about 1500 characters before the next newline overflows the stack.
     */
    private String removeHelmTemplateExpressions(String content) {
        return content.replaceAll("\\{\\{(?:(?!}}|[\\r\\n]).)*}}", "");
    }
}
