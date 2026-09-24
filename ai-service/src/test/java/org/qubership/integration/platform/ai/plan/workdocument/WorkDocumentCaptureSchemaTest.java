package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkDocumentCaptureSchemaTest {

  private static final Set<String> SERVER_OWNED =
      Set.of(
          "id",
          "documentId",
          "approval",
          "approvalReference",
          "progress",
          "schemaVersion",
          "binding",
          "catalogId",
          "protocol",
          "method",
          "path",
          "contentHash",
          "revision",
          "revisionId");

  @Test
  void generatedSchemaKeepsRegionsAndRecordsInHomogeneousLists() {
    JsonObjectSchema capture = WorkDocumentCaptureSchema.captureSchema();
    for (String list :
        List.of(
            "requirements",
            "steps",
            "connections",
            "sequenceGroups",
            "conditionGroups",
            "splitGroups",
            "loopGroups",
            "retryGroups",
            "errorScopeGroups",
            "transfers",
            "rules",
            "retainedValues",
            "deletes")) {
      assertInstanceOf(JsonArraySchema.class, capture.properties().get(list), list);
    }
    assertFalse(Boolean.TRUE.equals(capture.additionalProperties()));
  }

  @Test
  void generatedSchemaOmitsServerOwnedFields() {
    List<String> names = new ArrayList<>();
    collect(WorkDocumentCaptureSchema.captureSchema(), names);
    for (String owned : SERVER_OWNED) {
      assertFalse(names.contains(owned), owned + " in " + names);
    }
    assertTrue(names.contains("alias"));
    assertTrue(names.contains("existingId"));
    assertTrue(names.contains("outcome"));
  }

  private static void collect(JsonSchemaElement element, List<String> names) {
    if (element instanceof JsonObjectSchema object) {
      object.properties().forEach(
          (name, child) -> {
            names.add(name);
            collect(child, names);
          });
    } else if (element instanceof JsonArraySchema array && array.items() != null) {
      collect(array.items(), names);
    }
  }
}
