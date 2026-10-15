package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;

class ElementSchemaParityTest {
  private static final Path SOURCE = Path.of("..", "schemas", "src", "main", "resources", "conf-model");
  private static final Path DESCRIPTORS = Path.of("..", "integration-build-pipeline", "src", "main",
      "resources", "elements");
  private static final Map<String, Set<String>> LEGACY_PROPERTIES = Map.of(
      "jms-trigger", Set.of("enableIdempotency", "idempotencyKeyExpression", "idempotencyKeyExpiry",
          "actionOnDuplicate", "chainTriggerElementId", "chainCallTimeout"),
      "async-api-trigger", Set.of("acknowledgeMode", "jsonRequestSchema"),
      "http-trigger", Set.of("invalidURI"));

  private final SchemaResourceLoader loader = new SchemaResourceLoader();
  private final QipSchemaYamlParser parser = new QipSchemaYamlParser();
  private final SchemaRefResolver resolver = new SchemaRefResolver(loader, parser);
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void everySchemaKeepsItsPropertiesAndPackagedSource() throws Exception {
    List<String> differences = new ArrayList<>();
    try (var files = Files.list(SOURCE.resolve("element"))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".schema.yaml")).toList()) {
        String type = file.getFileName().toString().replace(".schema.yaml", "");
        if (type.equals("element")) {
          continue;
        }
        assertEquals(Files.readString(file), loader.readElementSchemaYaml(type), type + " packaged schema");
        JsonNode root = resolver.loadDocumentRoot(QipConfModelUris.elementModelUri(type));
        Set<String> expected = new TreeSet<>();
        collectKeys(root.path("properties").path("properties"), expected, new HashSet<>());
        Set<String> actual = ElementPropertiesSchemaModelBuilder.build(type, resolver).propertyDefs().keySet();
        if (!expected.equals(actual)) {
          differences.add(type + " expected " + expected + ", got " + actual);
        }
      }
    }
    assertEquals(List.of(), differences);
  }

  @Test
  void everyDeclaredRuntimePropertyHasASharedSchema() throws Exception {
    var contract = new ClasspathCompilerContractRepository().require(CompilerContract.V1);
    List<String> differences = new ArrayList<>();
    Set<String> descriptorTypes = new HashSet<>();
    try (var files = Files.walk(DESCRIPTORS)) {
      for (Path file : files.filter(path -> path.getFileName().toString().matches("description\\.ya?ml")).toList()) {
        JsonNode descriptor = parser.parseYaml(Files.readString(file));
        String type = descriptor.path("name").asText();
        descriptorTypes.add(type);
        if (!contract.elements().containsKey(type)) {
          continue;
        }
        Set<String> keys = ElementPropertiesSchemaModelBuilder.build(type, resolver).propertyDefs().keySet();
        var service = DeterministicElementSchemaService.createForUnitTests(mapper);
        for (JsonNode group : descriptor.path("properties")) {
          for (JsonNode property : group) {
            String key = property.path("name").asText();
            if ("${filter-header-allowlist-name}".equals(key)) {
              key = "requestFilterHeaderAllowlist";
            }
            if (!property.path("type").asText().equals("custom") && !keys.contains(key)
                && !LEGACY_PROPERTIES.getOrDefault(type, Set.of()).contains(key)) {
              differences.add(type + "." + key);
            }
            if (keys.contains(key) && !property.path("type").asText().equals("custom")) {
              List<JsonNode> values = new ArrayList<>();
              property.path("allowedValues").forEach(value -> values.add(property.path("multiple").asBoolean()
                  ? mapper.createArrayNode().add(value) : value));
              if (property.has("default")) {
                values.add(property.get("default"));
              }
              for (JsonNode value : values) {
                Object coerced = service.coercePatchPropertyValue(type, key,
                    value.isContainerNode() ? value.toString() : value.asText());
                var error = service.validateCapturePropertyValue(type, key, mapper.valueToTree(coerced));
                if (error.isPresent()) {
                  differences.add(type + "." + key + " catalog value " + value + ": " + error.get());
                }
              }
            }
          }
        }
      }
    }
    for (String type : contract.elements().keySet()) {
      if ((ChainElementFamilies.isTrigger(type) || ChainElementFamilies.isSender(type))
          && ChainElementFamilies.bindingMode(type) != ChainElementFamilies.BindingMode.UNSUPPORTED_IN_CREATE
          && !descriptorTypes.contains(type)) {
        differences.add(type + " has no runtime descriptor");
      }
    }
    assertEquals(List.of(), differences);
  }

  @Test
  void defaultsConformToTheirSchemaAfterYamlParsing() throws Exception {
    List<String> differences = new ArrayList<>();
    try (var files = Files.walk(SOURCE)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".schema.yaml")).toList()) {
        JsonNode raw = parser.parseYaml(Files.readString(file));
        assertEquals(Files.readString(file), loader.readSchemaYamlByModelUri(raw.path("$id").asText()),
            file + " packaged schema");
        JsonNode root = resolver.loadDocumentRoot(raw.path("$id").asText());
        checkDefaults(root, raw.path("$id").asText(), differences);
      }
    }
    assertEquals(List.of(), differences);
  }

  private void checkDefaults(JsonNode schema, String path, List<String> differences) {
    if (schema.isObject()) {
      if (schema.has("default")) {
        var errors = resolver.validate(schema, schema.get("default"));
        if (!errors.isEmpty()) {
          differences.add(path + ": " + errors);
        }
      }
      schema.properties().forEach(entry -> {
        if (!Set.of("default", "enum", "const", "examples").contains(entry.getKey())) {
          checkDefaults(entry.getValue(), path + "/" + entry.getKey(), differences);
        }
      });
    } else if (schema.isArray()) {
      for (int i = 0; i < schema.size(); i++) {
        checkDefaults(schema.get(i), path + "/" + i, differences);
      }
    }
  }

  private void collectKeys(JsonNode schema, Set<String> keys, Set<String> visited) {
    if (schema.has("$ref")) {
      String ref = schema.get("$ref").asText();
      assertTrue(ref.startsWith(QipConfModelUris.CONF_MODEL_PREFIX), ref);
      if (visited.add(ref)) {
        collectKeys(resolver.resolveRef(null, "", ref, new ArrayDeque<>()), keys, visited);
      }
      return;
    }
    schema.path("properties").fieldNames().forEachRemaining(keys::add);
    for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
      for (JsonNode child : schema.path(keyword)) {
        collectKeys(child, keys, visited);
      }
    }
    for (String keyword : List.of("then", "else")) {
      if (schema.has(keyword)) {
        collectKeys(schema.get(keyword), keys, visited);
      }
    }
  }
}
