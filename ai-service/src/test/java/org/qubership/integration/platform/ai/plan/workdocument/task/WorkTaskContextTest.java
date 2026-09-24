package org.qubership.integration.platform.ai.plan.workdocument.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;

class WorkTaskContextTest {

  @Test
  void scopedMappingPromptOmitsUnrelatedSchemaAndRules() throws Exception {
    String prompt = prompt();

    assertTrue(prompt.contains("RELATED_SCHEMA_BODY"));
    assertTrue(prompt.contains("catalog://schema-trigger"));
    assertTrue(prompt.contains("hash-schema-trigger"));
    assertTrue(prompt.contains("RELATED_RULE_BEHAVIOR"));
    assertFalse(prompt.contains("UNRELATED_SCHEMA_BODY"));
    assertFalse(prompt.contains("catalog://schema-other"));
    assertFalse(prompt.contains("UNRELATED_RULE_BEHAVIOR"));
  }

  @Test
  void promptKeepsRuntimeCatalogConstraintAndGoverningSource() throws Exception {
    String prompt = prompt();

    assertTrue(prompt.contains("runtime-catalog-only"));
    assertTrue(prompt.contains("GOVERNING_SOURCE_EVIDENCE"));
    assertFalse(prompt.contains("UNRELATED_SOURCE_EVIDENCE"));
  }

  private static String prompt() throws Exception {
    WorkDocumentState state = document();
    return WorkTaskContext.prompt(
        state,
        new WorkTaskScope(
            "map-transfer-a",
            state.revision(),
            WorkStage.DATA_BEHAVIOR,
            "mapping",
            List.of("transfer-a"),
            false,
            true,
            false,
            List.of(),
            List.of()),
        new WorkTaskMaterials(
            List.of(
                new SchemaFragment(
                    "schema-trigger",
                    "trigger",
                    "payload",
                    "hash-schema-trigger",
                    "catalog://schema-trigger",
                    "RELATED_SCHEMA_BODY"),
                new SchemaFragment(
                    "schema-other",
                    "other-step",
                    "payload",
                    "hash-schema-other",
                    "catalog://schema-other",
                    "UNRELATED_SCHEMA_BODY")),
            List.of("runtime-catalog-only"),
            Map.of(
                "source-gov", "GOVERNING_SOURCE_EVIDENCE",
                "source-other", "UNRELATED_SOURCE_EVIDENCE")));
  }

  static String documentJson() {
    return DOCUMENT_JSON;
  }

  private static WorkDocumentState document() throws Exception {
    ChainWorkDocument document =
        new ObjectMapper().readValue(DOCUMENT_JSON, ChainWorkDocument.class);
    return new WorkDocumentState("rev-context", document);
  }

  private static final String DOCUMENT_JSON =
      """
      {
        "schemaVersion": 1,
        "documentId": "doc-map",
        "sources": [
          {
            "id": "source-gov",
            "role": "request",
            "contentReference": "artifact://gov",
            "contentHash": "hash-gov",
            "originalName": "governing.md",
            "suppliedIdentifier": "GOV-1",
            "correctionOf": []
          },
          {
            "id": "source-other",
            "role": "note",
            "contentReference": "artifact://other",
            "contentHash": "hash-other",
            "originalName": "other.md",
            "suppliedIdentifier": "OTHER",
            "correctionOf": []
          }
        ],
        "requirements": [
          {
            "id": "req-a",
            "text": "Map the order name",
            "sourceIds": ["source-gov"],
            "supersededRequirementId": ""
          }
        ],
        "flow": {
          "steps": [
            {
              "id": "trigger",
              "kind": "TRIGGER",
              "label": "Order",
              "intent": "Receive the order",
              "sourceIds": ["source-gov"],
              "requirementIds": ["req-a"],
              "binding": null,
              "data": {"transfers": [], "retainedValues": []}
            },
            {
              "id": "call",
              "kind": "SERVICE_CALL",
              "label": "Create task",
              "intent": "Create the task",
              "sourceIds": ["source-gov"],
              "requirementIds": ["req-a"],
              "binding": null,
              "data": {
                "transfers": [
                  {
                    "id": "transfer-a",
                    "sourcePorts": [{"stepId": "trigger", "portName": "payload"}],
                    "targetPort": {"stepId": "call", "portName": "request"},
                    "requirementIds": ["req-a"],
                    "rules": [
                      {
                        "id": "rule-a",
                        "sources": [],
                        "target": {
                          "kind": "STEP_PORT",
                          "stepId": "call",
                          "port": "OUTBOUND_REQUEST",
                          "fieldPath": "$.Subject",
                          "retainedValueId": ""
                        },
                        "constants": [],
                        "behavior": "RELATED_RULE_BEHAVIOR",
                        "evidenceIds": ["source-gov"]
                      }
                    ],
                    "decision": "UNSPECIFIED"
                  },
                  {
                    "id": "transfer-b",
                    "sourcePorts": [{"stepId": "other-step", "portName": "payload"}],
                    "targetPort": {"stepId": "call", "portName": "other"},
                    "requirementIds": [],
                    "rules": [
                      {
                        "id": "rule-b",
                        "sources": [],
                        "target": {
                          "kind": "STEP_PORT",
                          "stepId": "call",
                          "port": "OUTBOUND_REQUEST",
                          "fieldPath": "$.Other",
                          "retainedValueId": ""
                        },
                        "constants": [],
                        "behavior": "UNRELATED_RULE_BEHAVIOR",
                        "evidenceIds": ["source-other"]
                      }
                    ],
                    "decision": "UNSPECIFIED"
                  }
                ],
                "retainedValues": []
              }
            }
          ],
          "connections": [
            {
              "id": "link-1",
              "sourceStepId": "trigger",
              "outcome": "success",
              "targetStepId": "call",
              "routingIntent": "Then create the task",
              "evidenceIds": ["source-gov"]
            }
          ],
          "sequenceGroups": [],
          "conditionGroups": [],
          "splitGroups": [],
          "loopGroups": [],
          "retryGroups": [],
          "errorScopeGroups": []
        },
        "progress": {
          "tasks": [],
          "findings": [],
          "questions": [],
          "approvalReference": "",
          "derivedResultReferences": [],
          "recheckStages": []
        }
      }
      """;
}
