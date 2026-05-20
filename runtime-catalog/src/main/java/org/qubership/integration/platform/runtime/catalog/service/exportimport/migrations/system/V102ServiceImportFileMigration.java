package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.system;

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

    public static String generateOperationName(ObjectNode operationNode) {
        String id = operationNode.path("id").asText();
        String method = operationNode.path("method").asText();
        String path = operationNode.path("path").asText();
        return Stream.of(id, method, path)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("-"));
    }
}
