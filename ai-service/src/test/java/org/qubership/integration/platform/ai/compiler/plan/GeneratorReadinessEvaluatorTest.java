package org.qubership.integration.platform.ai.compiler.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/**
 * Deterministic layer only: given classified intents and a graph, which signals are ready. Intent
 * classification itself (keyword/negation/paraphrase) is the LLM classifier's job and is not tested
 * here.
 */
class GeneratorReadinessEvaluatorTest {

  private static final Set<String> NO_INTENTS = Set.of();

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final GeneratorReadinessEvaluator evaluator =
      new GeneratorReadinessEvaluator(
          DeterministicElementSchemaService.createForUnitTests(objectMapper), objectMapper);

  @Test
  void intentSignalMatchesWhenConceptClassified() {
    var result =
        evaluator.evaluate(List.of("timeout_intent"), emptyGraph(), Set.of("timeout"));
    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("timeout_intent"));
  }

  @Test
  void intentSignalSkipsWhenConceptNotClassified() {
    assertEquals(
        GeneratorPlanStatus.SKIPPED,
        evaluator.evaluate(List.of("timeout_intent"), emptyGraph(), NO_INTENTS).status());
    assertEquals(
        GeneratorPlanStatus.SKIPPED,
        evaluator.evaluate(List.of("retry_intent"), emptyGraph(), Set.of("timeout")).status());
  }

  @Test
  void retryAndMonitoringAndCompositionAndParallelAndLoopIntents() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("retry_intent"), emptyGraph(), Set.of("retry")).status());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("monitoring_intent"), emptyGraph(), Set.of("monitoring")).status());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("composition_intent"), emptyGraph(), Set.of("composition")).status());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("parallel_intent"), emptyGraph(), Set.of("parallel")).status());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("loop_intent"), emptyGraph(), Set.of("loop")).status());
  }

  @Test
  void explicitErrorHandlingMatchesWhenRequestedAndGraphIncomplete() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator
            .evaluate(List.of("explicit_error_handling"), emptyGraph(), Set.of("error_handling"))
            .status());
  }

  @Test
  void explicitErrorHandlingSkipsWhenCompleteTryCatchAlreadyInGraph() {
    assertEquals(
        GeneratorPlanStatus.SKIPPED,
        evaluator
            .evaluate(
                List.of("explicit_error_handling"),
                completeTryCatchGraph(),
                Set.of("error_handling"))
            .status());
  }

  @Test
  void unwantedErrorHandlingMatchesWhenGraphHasEhWithoutIntent() {
    var result =
        evaluator.evaluate(List.of("unwanted_error_handling_nodes"), completeTryCatchGraph(), NO_INTENTS);
    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("unwanted_error_handling_nodes"));
  }

  @Test
  void unwantedErrorHandlingSkipsWhenErrorHandlingIntentPresent() {
    assertEquals(
        GeneratorPlanStatus.SKIPPED,
        evaluator
            .evaluate(
                List.of("unwanted_error_handling_nodes"),
                completeTryCatchGraph(),
                Set.of("error_handling"))
            .status());
  }

  @Test
  void chainFailureHandlerIntentMatchesWhenRequested() {
    var result =
        evaluator.evaluate(
            List.of("chain_failure_handler_intent"),
            emptyGraph(),
            Set.of("chain_failure_handler"));
    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("chain_failure_handler_intent"));
  }

  @Test
  void abacIntentMatchesWhenClassified() {
    var result = evaluator.evaluate(List.of("abac_intent"), emptyGraph(), Set.of("abac"));
    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("abac_intent"));
  }

  @Test
  void rbacMatchesViaIntent() {
    var result = evaluator.evaluate(List.of("rbac"), emptyGraph(), Set.of("rbac"));
    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("rbac"));
  }

  @Test
  void rbacMatchesViaGraphPropertyWithoutIntent() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("sec", "Sec"),
            List.of(
                new ChainPlanNode(
                    "1",
                    "http-trigger",
                    "Trigger",
                    null,
                    null,
                    List.of(new PlanProperty("accessControlType", "RBAC")))),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY, evaluator.evaluate(List.of("rbac"), graph, NO_INTENTS).status());
  }

  @Test
  void httpTriggerNodesMatchesWhenPresent() {
    var graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("http", "HTTP"),
            List.of(new ChainPlanNode("1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    var result = evaluator.evaluate(List.of("http_trigger_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("http_trigger_nodes"));
  }

  @Test
  void incompleteHttpTriggerEndpointMatchesWhenEndpointPropertiesAreMissing() {
    var graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("http", "HTTP"),
            List.of(new ChainPlanNode("1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    var result = evaluator.evaluate(List.of("incomplete_http_trigger_endpoint"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("incomplete_http_trigger_endpoint"));
  }

  @Test
  void incompleteHttpTriggerEndpointSkipsWhenEndpointPropertiesArePresent() {
    var graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("http", "HTTP"),
            List.of(
                new ChainPlanNode(
                    "1",
                    "http-trigger",
                    "Trigger",
                    null,
                    null,
                    List.of(
                        new PlanProperty("contextPath", "/hello"),
                        new PlanProperty("httpMethodRestrict", "GET"),
                        new PlanProperty("externalRoute", "true")))),
            List.of());
    var result = evaluator.evaluate(List.of("incomplete_http_trigger_endpoint"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.SKIPPED, result.status());
  }

  @Test
  void fileOperationsIntentAndNodes() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator
            .evaluate(List.of("file_operations_intent"), emptyGraph(), Set.of("file_operations"))
            .status());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("file", "File"),
            List.of(new ChainPlanNode("1", "file-read", "Read", null, null, List.of())),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("file_operations_nodes"), graph, NO_INTENTS).status());
  }

  @Test
  void sftpTriggerIntentAndNodes() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator
            .evaluate(List.of("sftp_trigger_intent"), emptyGraph(), Set.of("sftp_trigger"))
            .status());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("sftp", "SFTP"),
            List.of(new ChainPlanNode("1", "sftp-trigger-2", "SFTP Trigger", null, null, List.of())),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("sftp_trigger_nodes"), graph, NO_INTENTS).status());
  }

  @Test
  void sdsTriggerIntentAndNodes() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("sds_trigger_intent"), emptyGraph(), Set.of("sds_trigger")).status());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("sds", "SDS"),
            List.of(new ChainPlanNode("1", "sds-trigger", "SDS Trigger", null, null, List.of())),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("sds_trigger_nodes"), graph, NO_INTENTS).status());
  }

  @Test
  void contextStorageIntentAndNodes() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator
            .evaluate(List.of("context_storage_intent"), emptyGraph(), Set.of("context_storage"))
            .status());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("ctx", "Context"),
            List.of(
                new ChainPlanNode("1", "context-storage", "Context storage", null, null, List.of())),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("context_storage_nodes"), graph, NO_INTENTS).status());
  }

  @Test
  void messagingIntentAndNodes() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("messaging_intent"), emptyGraph(), Set.of("messaging")).status());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("msg", "Messaging"),
            List.of(
                new ChainPlanNode("1", "pubsub-trigger", "Pub/Sub Trigger", null, null, List.of())),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("messaging_nodes"), graph, NO_INTENTS).status());
  }

  @Test
  void xsltIntentAndNodes() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("xslt_intent"), emptyGraph(), Set.of("xslt")).status());

    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("xslt", "XSLT"),
            List.of(new ChainPlanNode("1", "xslt", "Transform", null, null, List.of())),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("xslt_nodes"), graph, NO_INTENTS).status());
  }

  @Test
  void branchingIntentMatchesWhenClassified() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("branching_intent"), emptyGraph(), Set.of("branching")).status());
  }

  @Test
  void branchingWithoutRoutingNodesMatchesWhenIntentPresentAndNoRouting() {
    var result =
        evaluator.evaluate(
            List.of("branching_without_routing_nodes"), emptyGraph(), Set.of("branching"));

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("branching_without_routing_nodes"));
  }

  @Test
  void branchingWithoutRoutingNodesSkipsWhenModernRoutingExists() {
    var result =
        evaluator.evaluate(
            List.of("branching_without_routing_nodes"),
            fortuneCompleteRoutingGraph(),
            Set.of("branching"));

    assertEquals(GeneratorPlanStatus.SKIPPED, result.status());
  }

  @Test
  void incompleteRoutingNodesMatchesConditionWithoutIfChild() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("route", "Route"),
            List.of(
                new ChainPlanNode("route", "condition", "Route", null, null, List.of()),
                new ChainPlanNode("n1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());

    var result = evaluator.evaluate(List.of("incomplete_routing_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
  }

  @Test
  void incompleteRoutingNodesMatchesIfWithoutConditionProperty() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("route", "Route"),
            List.of(
                new ChainPlanNode("route", "condition", "Route", null, null, List.of()),
                new ChainPlanNode("if-fr", "if", "If FR", "route", null, List.of())),
            List.of());

    var result = evaluator.evaluate(List.of("incomplete_routing_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
  }

  @Test
  void incompleteRoutingNodesMatchesDeprecatedChoice() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("legacy", "Legacy"),
            List.of(new ChainPlanNode("c1", "choice", "Choice", null, null, List.of())),
            List.of());

    var result = evaluator.evaluate(List.of("incomplete_routing_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
  }

  @Test
  void completeRoutingGraphSkipsNeedBasedRoutingSignals() {
    var without =
        evaluator.evaluate(
            List.of("branching_without_routing_nodes"),
            fortuneCompleteRoutingGraph(),
            Set.of("branching"));
    var incomplete =
        evaluator.evaluate(
            List.of("incomplete_routing_nodes"), fortuneCompleteRoutingGraph(), Set.of("branching"));
    var routingNodes =
        evaluator.evaluate(List.of("routing_nodes"), fortuneCompleteRoutingGraph(), NO_INTENTS);

    assertEquals(GeneratorPlanStatus.SKIPPED, without.status());
    assertEquals(GeneratorPlanStatus.SKIPPED, incomplete.status());
    assertEquals(GeneratorPlanStatus.READY, routingNodes.status());
    assertTrue(routingNodes.matchedSignals().contains("routing_nodes"));
  }

  @Test
  void routingNodesMatchesWhenModernRoutingExists() {
    var result =
        evaluator.evaluate(List.of("routing_nodes"), fortuneCompleteRoutingGraph(), NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("routing_nodes"));
  }

  @Test
  void completeTryCatchGraphSkipsIncompleteTryCatchSignal() {
    var result =
        evaluator.evaluate(List.of("incomplete_try_catch_nodes"), completeTryCatchGraph(), NO_INTENTS);

    assertEquals(GeneratorPlanStatus.SKIPPED, result.status());
  }

  @Test
  void incompleteTryCatchShellMarksEhReady() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("eh", "EH"),
            List.of(
                new ChainPlanNode(
                    "eh", "try-catch-finally-2", "Error Handling", null, null, List.of())),
            List.of());

    var result = evaluator.evaluate(List.of("incomplete_try_catch_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("incomplete_try_catch_nodes"));
  }

  @Test
  void deprecatedTryCatchTypesMarkIncompleteTryCatchReady() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("legacy", "Legacy"),
            List.of(new ChainPlanNode("c1", "catch", "Catch", null, null, List.of())),
            List.of());

    var result = evaluator.evaluate(List.of("incomplete_try_catch_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
  }

  @Test
  void chainCallNodesMatchGraphType() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("sub", "Sub"),
            List.of(new ChainPlanNode("1", "chain-call-2", "Call", null, null, List.of())),
            List.of());

    var result = evaluator.evaluate(List.of("chain_call_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
  }

  @Test
  void loopNodesMatchGraphType() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("loop", "Loop"),
            List.of(new ChainPlanNode("1", "loop-2", "Loop", null, null, List.of())),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("loop_nodes"), graph, NO_INTENTS).status());
  }

  @Test
  void parallelNodesMatchGraphType() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("split", "Split"),
            List.of(new ChainPlanNode("1", "split-2", "Split", null, null, List.of())),
            List.of());
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("parallel_nodes"), graph, NO_INTENTS).status());
  }

  @Test
  void mcpTriggerIntentMatchesWhenClassified() {
    var result =
        evaluator.evaluate(List.of("mcp_trigger_intent"), emptyGraph(), Set.of("mcp_trigger"));

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("mcp_trigger_intent"));
  }

  @Test
  void mcpTriggerNodesMatchesWhenPresent() {
    var graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("mcp", "MCP"),
            List.of(new ChainPlanNode("1", "mcp-trigger", "MCP Trigger", null, null, List.of())),
            List.of());
    var result = evaluator.evaluate(List.of("mcp_trigger_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("mcp_trigger_nodes"));
  }

  @Test
  void alwaysReadyMatchesRegardless() {
    assertEquals(
        GeneratorPlanStatus.READY,
        evaluator.evaluate(List.of("always_ready"), emptyGraph(), NO_INTENTS).status());
  }

  @Test
  void emptySignalsBlocked() {
    assertEquals(
        GeneratorPlanStatus.BLOCKED, evaluator.evaluate(List.of(), emptyGraph(), NO_INTENTS).status());
  }

  @Test
  void requiresIntentClassificationDetectsIntentSignals() {
    assertTrue(
        GeneratorReadinessEvaluator.requiresIntentClassification(List.of("routing_nodes", "timeout_intent")));
    assertEquals(
        false,
        GeneratorReadinessEvaluator.requiresIntentClassification(List.of("routing_nodes", "loop_nodes")));
  }

  private static ChainPlanGraph completeTryCatchGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("eh", "EH"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("eh", "try-catch-finally-2", "Error Handling", null, null, List.of()),
            new ChainPlanNode("try", "try-2", "Try", "eh", null, List.of()),
            new ChainPlanNode(
                "catch",
                "catch-2",
                "Catch",
                "eh",
                null,
                List.of(new PlanProperty("exception", "java.lang.Exception"))),
            new ChainPlanNode("script", "script", "Script", "try", null, List.of())),
        List.of());
  }

  private static ChainPlanGraph fortuneCompleteRoutingGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Fortune API", "Fortune API with language routing"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "HTTP Trigger", null, null, List.of()),
            new ChainPlanNode("parse-lang", "script", "Parse lang", null, null, List.of()),
            new ChainPlanNode("route", "condition", "Route by language", null, null, List.of()),
            new ChainPlanNode(
                "if-fr",
                "if",
                "French branch",
                "route",
                null,
                List.of(new PlanProperty("condition", "${exchangeProperty.preferredLang} == 'fr'"))),
            new ChainPlanNode("else-en", "else", "Default branch", "route", null, List.of()),
            new ChainPlanNode("fr-response", "script", "FR response", "if-fr", null, List.of()),
            new ChainPlanNode("en-response", "script", "EN response", "else-en", null, List.of())),
        List.of());
  }

  private static ChainPlanGraph emptyGraph() {
    return new ChainPlanGraph("1.0", new ChainSection("g", "G"), List.of(), List.of());
  }

  @Test
  void rbacRolesMissingWhenRbacWithoutRoles() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure", "Secure"),
            List.of(
                new ChainPlanNode(
                    "trigger",
                    "http-trigger",
                    "Trigger",
                    null,
                    null,
                    List.of(new PlanProperty("accessControlType", "RBAC")))),
            List.of());

    assertTrue(evaluator.evaluate(List.of("rbac_roles_missing"), graph, NO_INTENTS).matchedSignals()
        .contains("rbac_roles_missing"));
  }

  @Test
  void rbacRolesMissingFalseWhenRolesPopulated() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure", "Secure"),
            List.of(
                new ChainPlanNode(
                    "trigger",
                    "http-trigger",
                    "Trigger",
                    null,
                    null,
                    List.of(
                        new PlanProperty("accessControlType", "RBAC"),
                        new PlanProperty("roles", "[\"qip-viewer\"]")))),
            List.of());

    assertEquals(
        GeneratorPlanStatus.SKIPPED,
        evaluator.evaluate(List.of("rbac_roles_missing"), graph, NO_INTENTS).status());
  }

  @Test
  void rbacRolesMissingTrueWhenRolesEmptyArray() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("secure", "Secure"),
            List.of(
                new ChainPlanNode(
                    "trigger",
                    "http-trigger",
                    "Trigger",
                    null,
                    null,
                    List.of(
                        new PlanProperty("accessControlType", "RBAC"),
                        new PlanProperty("roles", "[]")))),
            List.of());

    assertTrue(evaluator.evaluate(List.of("rbac_roles_missing"), graph, NO_INTENTS).matchedSignals()
        .contains("rbac_roles_missing"));
  }

  @Test
  void unmetCompletenessFlagsIncompleteServiceCallBindings() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", "Proxy"),
            List.of(
                new ChainPlanNode("call-1", "service-call", "Call inventory", null, null, List.of())),
            List.of());

    List<String> unmet =
        evaluator.unmetCompleteness(List.of("incomplete_service_call_bindings"), graph);

    assertEquals(List.of("incomplete_service_call_bindings"), unmet);
    assertEquals(List.of("call-1"), evaluator.serviceCallNodesMissingBindings(graph));
  }

  @Test
  void incompleteServiceCallBindingsStillTrueWhenOperationBranchIsSchemaInvalid() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", "Proxy"),
            List.of(
                new ChainPlanNode(
                    "call-1",
                    "service-call",
                    "Call inventory",
                    null,
                    null,
                    List.of(
                        new PlanProperty("integrationSystemId", "sys-1"),
                        new PlanProperty("integrationOperationId", "op-1")))),
            List.of());

    var result =
        evaluator.evaluate(List.of("incomplete_service_call_bindings"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertEquals(List.of("call-1"), evaluator.serviceCallNodesMissingBindings(graph));
    assertTrue(evaluator.serviceCallBindingSchemaFailure(graph).isPresent());
    assertTrue(evaluator.serviceCallBindingSchemaFailure(graph).orElseThrow().contains("call-1"));
  }

  @Test
  void incompleteServiceCallBindingsFalseWhenOperationBranchMatchesSchema() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("proxy", "Proxy"),
            List.of(
                new ChainPlanNode(
                    "call-1",
                    "service-call",
                    "Call inventory",
                    null,
                    null,
                    List.of(
                        new PlanProperty("integrationSystemId", "sys-1"),
                        new PlanProperty("integrationSpecificationGroupId", "group-1"),
                        new PlanProperty("integrationSpecificationId", "spec-1"),
                        new PlanProperty("integrationOperationId", "op-1"),
                        new PlanProperty("integrationOperationProtocolType", "http"),
                        new PlanProperty("integrationOperationMethod", "GET"),
                        new PlanProperty("systemType", "EXTERNAL"),
                        new PlanProperty("integrationOperationPath", "/store/inventory")))),
            List.of());

    var result =
        evaluator.evaluate(List.of("incomplete_service_call_bindings"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.SKIPPED, result.status());
    assertTrue(evaluator.serviceCallNodesMissingBindings(graph).isEmpty());
  }

  @Test
  void unmetCompletenessReturnsOnlyGapSignals() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("fortune", "Fortune"),
            List.of(new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of());

    List<String> unmet =
        evaluator.unmetCompleteness(
            List.of("script_nodes_missing_body", "rbac", "backend_integration_intent"), graph);

    assertEquals(List.of("script_nodes_missing_body"), unmet);
  }

  @Test
  void omittedPlaceholderScriptBodyCountsAsMissing() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("fortune", "Fortune"),
            List.of(
                new ChainPlanNode(
                    "script-1",
                    "script",
                    "Script",
                    null,
                    null,
                    List.of(new PlanProperty("script", "<script body omitted, 379 chars>")))),
            List.of());

    assertEquals(List.of("script-1"), evaluator.scriptNodesMissingBody(graph));
  }

  @Test
  void unmetCompletenessFlagsIncompleteRoutingWithEmptyIntents() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("route", "Route"),
            List.of(
                new ChainPlanNode("route", "condition", "Route", null, null, List.of()),
                new ChainPlanNode("if-fr", "if", "If FR", "route", null, List.of())),
            List.of());

    List<String> unmet =
        evaluator.unmetCompleteness(List.of("incomplete_routing_nodes"), graph);

    assertEquals(List.of("incomplete_routing_nodes"), unmet);
  }

  @Test
  void hasNodeTypeSignalsDoNotNpeWhenNodeTypeIsNull() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("partial", "Partial"),
            List.of(
                new ChainPlanNode("orphan", null, "Orphan", null, null, List.of()),
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());

    var http =
        evaluator.evaluate(List.of("http_trigger_nodes"), graph, NO_INTENTS);
    var serviceCall =
        evaluator.evaluate(List.of("service_call_nodes"), graph, NO_INTENTS);
    var incompleteTryCatch =
        evaluator.evaluate(List.of("incomplete_try_catch_nodes"), graph, NO_INTENTS);
    var routing =
        evaluator.evaluate(List.of("routing_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, http.status());
    assertTrue(http.matchedSignals().contains("http_trigger_nodes"));
    assertEquals(GeneratorPlanStatus.SKIPPED, serviceCall.status());
    assertEquals(GeneratorPlanStatus.SKIPPED, incompleteTryCatch.status());
    assertEquals(GeneratorPlanStatus.SKIPPED, routing.status());
  }

  @Test
  void incompleteTryCatchWithNullTypeSiblingDoesNotNpe() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("eh", "EH"),
            List.of(
                new ChainPlanNode("ghost", null, "Ghost", null, null, List.of()),
                new ChainPlanNode(
                    "eh", "try-catch-finally-2", "Error Handling", null, null, List.of())),
            List.of());

    var result = evaluator.evaluate(List.of("incomplete_try_catch_nodes"), graph, NO_INTENTS);

    assertEquals(GeneratorPlanStatus.READY, result.status());
    assertTrue(result.matchedSignals().contains("incomplete_try_catch_nodes"));
  }

  @Test
  void scriptNodesMissingBodyIgnoresNullTypeNodes() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("scripts", "Scripts"),
            List.of(
                new ChainPlanNode("ghost", null, "Ghost", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", null, null, List.of())),
            List.of());

    assertEquals(List.of("script-1"), evaluator.scriptNodesMissingBody(graph));
  }
}
