package org.qubership.integration.platform.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.quarkiverse.langchain4j.runtime.ToolsRecorder;
import io.quarkiverse.langchain4j.runtime.tool.ToolMethodCreateInfo;
import io.quarkus.test.junit.QuarkusTest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Prevents new server-owned fields from leaking into generated LLM tool schemas. */
@QuarkusTest
class ToolSchemaOwnershipRatchetTest {

  private static final Set<String> SERVER_OWNED_NAMES =
      Set.of(
          "artifactId",
          "compilerContractVersion",
          "contentHash",
          "conversationId",
          "normalizedFlowHash",
          "rendererVersion",
          "revisionId",
          "runId",
          "schemaVersion",
          "sourceHash",
          "systemType");

  /**
   * Existing capture contracts that need dedicated LLM-facing DTOs. Each removal is intentional
   * and does not require changing this allowlist.
   */
  private static final Set<SchemaField> KNOWN_DEBT =
      Set.of(
          new SchemaField("captureSelectedPattern", "capture.elementSkeleton.schemaVersion"),
          new SchemaField("captureConfiguredTriggerSet", "capture.schemaVersion"),
          new SchemaField("captureNamingManifest", "capture.schemaVersion"),
          new SchemaField("captureChainPlan", "graph.schemaVersion"),
          new SchemaField("captureChainPlan", "graph.chain.compilerContractVersion"),
          new SchemaField("captureChainStructure", "capture.graph.schemaVersion"),
          new SchemaField("captureChainStructure", "capture.graph.chain.compilerContractVersion"));

  @Test
  void generatedToolSchemasDoNotAddServerOwnedFields() {
    Set<SchemaField> leaks = serverOwnedFields();
    Set<SchemaField> unexpected = new TreeSet<>(leaks);
    unexpected.removeAll(KNOWN_DEBT);

    assertTrue(
        unexpected.isEmpty(),
        () -> "New LLM-visible server-owned schema fields: " + unexpected);
  }

  private static Set<SchemaField> serverOwnedFields() {
    Set<SchemaField> result = new TreeSet<>();
    for (List<ToolMethodCreateInfo> methods : ToolsRecorder.getMetadata().values()) {
      for (ToolMethodCreateInfo method : methods) {
        JsonObjectSchema parameters = method.toolSpecification().parameters();
        if (parameters == null) {
          continue;
        }
        parameters
            .properties()
            .forEach(
                (name, schema) ->
                    collect(method.methodName(), name, schema, new LinkedHashSet<>(), result));
      }
    }
    return result;
  }

  private static void collect(
      String methodName,
      String path,
      JsonSchemaElement schema,
      Set<String> ancestorNames,
      Set<SchemaField> result) {
    String name = path.substring(path.lastIndexOf('.') + 1).replace("[]", "");
    if (SERVER_OWNED_NAMES.contains(name)) {
      result.add(new SchemaField(methodName, path));
    }
    if (schema instanceof JsonObjectSchema object) {
      object
          .properties()
          .forEach(
              (childName, child) -> {
                if (ancestorNames.add(childName)) {
                  collect(methodName, path + "." + childName, child, ancestorNames, result);
                  ancestorNames.remove(childName);
                }
              });
    } else if (schema instanceof JsonArraySchema array) {
      collect(methodName, path + "[]", array.items(), ancestorNames, result);
    }
  }

  private record SchemaField(String methodName, String path) implements Comparable<SchemaField> {

    @Override
    public int compareTo(SchemaField other) {
      int methodComparison = methodName.compareTo(other.methodName);
      return methodComparison != 0 ? methodComparison : path.compareTo(other.path);
    }
  }
}
