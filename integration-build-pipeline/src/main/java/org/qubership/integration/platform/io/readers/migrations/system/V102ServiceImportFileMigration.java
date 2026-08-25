package org.qubership.integration.platform.io.readers.migrations.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class V102ServiceImportFileMigration implements ServiceImportFileMigration {
    @Override
    public int getVersion() {
        return 102;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) throws JsonProcessingException {
        log.debug("Applying service migration: {}", getVersion());
        ObjectNode result = fileNode.deepCopy();
        result.path("content").path("operations").forEach(operationNode -> {
            if (operationNode instanceof ObjectNode node
                    && StringUtils.isBlank(operationNode.path("name").asText())) {
                String name = generateOperationName(node);
                JsonNode nameNode = TextNode.valueOf(name);
                node.set("name", nameNode);
                log.debug("Set name for operation '{}': {}", operationNode.path("id").asText(), name);
            }
        });
        return result;
    }

    /**
     * Runs before V103, so the operation still carries {@code method}/{@code path}. It reads the api locators too,
     * so a name is generated the same way whichever shape reaches it: the method from {@code method}, else
     * {@code rpcMethod} or {@code operationType}; the path from {@code path}, else {@code channel} or
     * {@code package}.{@code service}.
     */
    public static String generateOperationName(ObjectNode operationNode) {
        String id = operationNode.path("id").asText();
        String method = firstNonBlank(operationNode, "method", "rpcMethod", "operationType");
        String path = pathLocator(operationNode);
        return Stream.of(id, method, path)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("-"));
    }

    private static String pathLocator(ObjectNode operationNode) {
        String path = operationNode.path("path").asText();
        if (StringUtils.isNotBlank(path)) {
            return path;
        }
        String channel = operationNode.path("channel").asText();
        if (StringUtils.isNotBlank(channel)) {
            return channel;
        }
        return Stream.of(operationNode.path("package").asText(), operationNode.path("service").asText())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("."));
    }

    private static String firstNonBlank(ObjectNode operationNode, String... fields) {
        return Stream.of(fields)
                .map(field -> operationNode.path(field).asText())
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }
}
