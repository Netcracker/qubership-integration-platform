package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;

import java.util.List;
import java.util.Map;

/**
 * Handlebars helper that combines multiple validation schemas from the 'after'
 * property into a single JSON Schema using oneOf semantics.
 *
 * <p>If there is one schema, it is returned as-is.
 * If there are multiple schemas, they are wrapped in a {@code {"oneOf": [...]}} envelope.
 */
@TemplatesHelper("combined-validation-schema")
public class ValidationSchemaHelper extends BaseHelper implements Helper<String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Object apply(String propertyName, Options options) {
        Object value = getPropertyValue(propertyName, options);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        List<String> schemas = list.stream()
                .filter(Map.class::isInstance)
                .map(item -> ((Map<?, ?>) item).get("schema"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(s -> !s.isEmpty())
                .toList();

        if (schemas.isEmpty()) {
            return null;
        }
        if (schemas.size() == 1) {
            return schemas.get(0);
        }

        try {
            ObjectNode combined = OBJECT_MAPPER.createObjectNode();
            combined.put("$schema", "http://json-schema.org/draft-07/schema#");
            ArrayNode oneOfArray = combined.putArray("oneOf");
            for (String schema : schemas) {
                oneOfArray.add(OBJECT_MAPPER.readTree(schema));
            }
            return OBJECT_MAPPER.writeValueAsString(combined);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error combining validation schemas", e);
        }
    }
}
