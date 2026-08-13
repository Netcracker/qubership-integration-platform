package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class DesignExecutionBriefFactoryTest {

  @Test
  void buildsBriefFromFlowWithTriggerPathAndBindings() {
    NormalizedDesignFlow flow =
        new NormalizedDesignFlow(
            "1",
            "flow-1",
            "HealthProxy",
            "Proxy inventory",
            new NormalizedDesignFlow.Trigger(
                "http", "client", "HTTP", "/health-proxy", "GET", List.of()),
            List.of(new NormalizedDesignFlow.Participant("client", "Client", "EXTERNAL", List.of())),
            List.of(
                new NormalizedDesignFlow.Step(
                    "call-1",
                    "service-call",
                    "cip",
                    "petstore",
                    "GET /store/inventory",
                    "",
                    List.of()),
                new NormalizedDesignFlow.Step(
                    "script-1", "script", "cip", "cip", "", "Return inventory JSON", List.of())),
            List.of(),
            List.of(),
            List.of(),
            List.of("RBAC role test-role", "No external route"),
            List.of());
    CatalogBindingResolution binding =
        new CatalogBindingResolution(
            "call-1",
            CatalogBindingResolution.Source.EXISTING_CATALOG,
            "sys-1",
            "grp-1",
            "spec-1",
            "op-1",
            null,
            "2024.4",
            "evidence-1");

    RequirementBrief brief = DesignExecutionBriefFactory.build(null, flow, List.of(binding));

    assertTrue(brief.inputs().stream().anyMatch(i -> i.contains("/health-proxy")));
    assertTrue(brief.inputs().stream().anyMatch(i -> i.contains("systemId=sys-1")));
    assertTrue(brief.constraints().stream().anyMatch(c -> c.contains("test-role")));
    assertTrue(brief.approvedDraftText().contains("integrationOperationId: op-1"));
    assertTrue(brief.facts().stream().anyMatch(f -> f.text().contains("/health-proxy")));
  }

  @Test
  void enrichesStoredBriefWithBindingIds() {
    RequirementBrief stored =
        new RequirementBrief(
            "goal",
            List.of("HTTP request to '/health-proxy'"),
            List.of("RBAC role 'test-role'"),
            List.of(),
            List.of(),
            "summary",
            null,
            "approved text",
            List.of());
    NormalizedDesignFlow flow =
        new NormalizedDesignFlow(
            "1",
            "flow-1",
            "HealthProxy",
            "",
            new NormalizedDesignFlow.Trigger(
                "http", "client", "HTTP", "/health-proxy", "GET", List.of()),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    CatalogBindingResolution binding =
        new CatalogBindingResolution(
            "call-1",
            CatalogBindingResolution.Source.EXISTING_CATALOG,
            "sys-9",
            "grp-9",
            "spec-9",
            "op-9",
            null,
            "2024.4",
            "evidence-9");

    RequirementBrief brief = DesignExecutionBriefFactory.build(stored, flow, List.of(binding));

    assertTrue(brief.approvedDraftText().contains("approved text"));
    assertTrue(brief.inputs().stream().anyMatch(i -> i.contains("systemId=sys-9")));
  }

  @Test
  void carriesApprovedFlowMappingsIntoTheExecutionBrief() {
    NormalizedDesignFlow flow =
        new NormalizedDesignFlow(
            "1",
            "flow-1",
            "HealthProxy",
            "Proxy inventory",
            new NormalizedDesignFlow.Trigger(
                "http", "client", "HTTP", "/health-proxy", "GET", List.of("fact-trigger")),
            List.of(
                new NormalizedDesignFlow.Participant(
                    "client", "Client", "EXTERNAL", List.of("fact-client")),
                new NormalizedDesignFlow.Participant(
                    "petstore", "Petstore", "EXTERNAL", List.of("fact-petstore"))),
            List.of(
                new NormalizedDesignFlow.Step(
                    "call-1",
                    "service-call",
                    "client",
                    "petstore",
                    "GET /store/inventory",
                    "",
                    List.of("fact-call"))),
            List.of(),
            List.of(),
            List.of(
                new NormalizedDesignFlow.DataMapping(
                    "map-init",
                    NormalizedDesignFlow.MappingStage.INITIALIZATION,
                    "step-trigger",
                    "call-1",
                    NormalizedDesignFlow.MappingMode.EXPLICIT,
                    List.of(
                        new NormalizedDesignFlow.MappingRule(
                            "$.request.id",
                            "$.headers.X-Request-Id",
                            null,
                            List.of("fact-rule"))),
                    List.of("fact-map"))),
            List.of(),
            List.of());

    RequirementBrief brief = DesignExecutionBriefFactory.build(null, flow, List.of());

    assertTrue(brief.dataMappings().stream().anyMatch(mapping -> mapping.mappingId().equals("map-init")));
    assertTrue(
        brief.dataMappings().getFirst().rules().stream()
            .anyMatch(rule -> rule.targetPath().equals("$.headers.X-Request-Id")));
  }
}
