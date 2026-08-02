package org.qubership.integration.platform.runtime.catalog.service.parsers.preprocessing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncApiVersion;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.AsyncapiSpecification;
import org.qubership.integration.platform.runtime.catalog.model.system.asyncapi.v3.AsyncapiV3Specification;
import org.qubership.integration.platform.runtime.catalog.service.parsers.asyncapi.AsyncApiV3Normalizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Preprocessing shared by the import path ({@code enrichSpecificationGroup}) and the persistence-free
 * operation-production core ({@code parseOperations}). Keeping it here guarantees both paths reshape the
 * raw source the same way instead of each reimplementing it.
 */
@Slf4j
@Component
public class SpecificationPreprocessing {

    private static final String OPEN_API_LABEL = "openapi";
    private static final String OPENAPI_32_VERSION_PREFIX = "3.2";
    private static final String OPENAPI_31_FALLBACK_VERSION = "3.1.0";
    private static final String ASYNCAPI_VERSION_FIELD = "asyncapi";

    private final AsyncApiV3Normalizer asyncApiV3Normalizer;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public SpecificationPreprocessing(
            AsyncApiV3Normalizer asyncApiV3Normalizer,
            @Qualifier("primaryObjectMapper") ObjectMapper jsonMapper,
            YAMLMapper yamlExportImportMapper
    ) {
        this.asyncApiV3Normalizer = asyncApiV3Normalizer;
        this.jsonMapper = jsonMapper;
        this.yamlMapper = yamlExportImportMapper;
    }

    /**
     * Rewrites an OpenAPI 3.2 version field to 3.1 so the specification can be parsed.
     *
     * <p>swagger-parser 2.1.x ships no 3.2 deserializer and rejects the version outright.
     * OpenAPI 3.2 stays backward compatible with 3.1, so 3.2 documents are parsed as 3.1;
     * 3.2-only constructs (the QUERY method, {@code $self}, extended media-type keys) are
     * dropped rather than failing the import. Only the text handed to the parser changes —
     * the stored specification source keeps its original version.
     */
    public static String downgradeUnsupportedOpenApiVersion(
            JsonNode specificationNode,
            String specificationText,
            Consumer<String> messageHandler
    ) {
        try {
            if (specificationNode == null || !specificationNode.has(OPEN_API_LABEL)) {
                return specificationText;
            }
            String version = specificationNode.get(OPEN_API_LABEL).asText("");
            if (!version.startsWith(OPENAPI_32_VERSION_PREFIX)) {
                return specificationText;
            }
            ((ObjectNode) specificationNode).put(OPEN_API_LABEL, OPENAPI_31_FALLBACK_VERSION);
            messageHandler.accept(String.format(
                    "OpenAPI %s imported with the 3.1 parser. 3.2-only features may be dropped. ",
                    version));
            return specificationNode.toString();
        } catch (Exception e) {
            log.warn("Could not normalize the OpenAPI version; passing the specification to the parser unchanged", e);
            return specificationText;
        }
    }

    /**
     * Reads an AsyncAPI document and normalizes AsyncAPI v3 into the internal v2-shaped model.
     * v2 documents pass through unchanged.
     */
    public AsyncapiSpecification readAsyncapiSpecification(String data) throws JsonProcessingException {
        ObjectMapper mapper = getMapper(data);
        JsonNode rootNode = mapper.readTree(data);
        String version = rootNode.path(ASYNCAPI_VERSION_FIELD).asText();
        if (AsyncApiVersion.detect(version) == AsyncApiVersion.V3) {
            AsyncapiV3Specification v3 = mapper.treeToValue(rootNode, AsyncapiV3Specification.class);
            return asyncApiV3Normalizer.normalize(v3);
        }
        return mapper.treeToValue(rootNode, AsyncapiSpecification.class);
    }

    private ObjectMapper getMapper(String data) {
        // A null document falls through to readTree, which reports it as an IllegalArgumentException the
        // read path already degrades on. This dispatch only has to not throw first.
        return data != null && data.trim().startsWith("{") ? jsonMapper : yamlMapper;
    }
}
