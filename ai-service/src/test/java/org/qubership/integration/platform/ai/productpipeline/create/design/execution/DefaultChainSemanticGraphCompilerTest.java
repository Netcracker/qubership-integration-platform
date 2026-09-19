package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ErrorHandler;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticContainment;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SplitMode;
import java.time.Instant;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ServiceCallFailureMode;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class DefaultChainSemanticGraphCompilerTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private final DeterministicElementSchemaService schemaService =
      DeterministicElementSchemaService.createForUnitTests(new ObjectMapper());
  private final ChainSemanticGraphCompiler compiler =
      new DefaultChainSemanticGraphCompiler(new DefaultChainSemanticRevisionValidator(), schemaService);

  @Test
  void projectsConditionReconvergenceThroughTheContainerOwner() {
    ChainSemanticRevision revision = withoutContainment(conditionRevision());
    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    assertEquals(
        Set.of(
            "edge-entry",
            "edge-true",
            "edge-false",
            "edge-true-join",
            "edge-false-join",
            "condition-1-if-true-branch-entry",
            "condition-1-else-false-branch-entry"),
        graph.edges().stream().map(ChainPlanEdge::edgeId).collect(Collectors.toSet()));
    assertEquals(
        1,
        graph.nodes().stream().filter(node -> "script-common".equals(node.nodeId())).count());
    assertEquals("if", node(graph, "condition-1-if-true-branch").type());
    assertEquals("else", node(graph, "condition-1-else-false-branch").type());
    assertEquals("condition-1", node(graph, "condition-1-if-true-branch").parentNodeId());
    assertEquals("condition-1", node(graph, "condition-1-else-false-branch").parentNodeId());
    assertEquals("condition-1-if-true-branch", node(graph, "script-true").parentNodeId());
    assertEquals("condition-1-else-false-branch", node(graph, "script-false").parentNodeId());
    assertNull(node(graph, "script-common").parentNodeId());
    assertEquals("status == 'ok'", property(node(graph, "condition-1-if-true-branch"), "condition"));
    assertEquals("1", property(node(graph, "condition-1-if-true-branch"), "priority"));
    assertEquals("condition-1-if-true-branch", edge(graph, "edge-true").fromNodeId());
    assertEquals("condition-1-else-false-branch", edge(graph, "edge-false").fromNodeId());
    assertEquals("condition-1", edge(graph, "edge-true").scopeNodeId());
    assertEquals("condition-1", edge(graph, "edge-true-join").fromNodeId());
    assertEquals("condition-1", edge(graph, "edge-false-join").fromNodeId());
    assertEquals("condition-1", edge(graph, "edge-true-join").scopeNodeId());
    assertEquals("revision-1", graph.chain().semanticRevisionId());
    assertEquals(CONTRACT.contractVersion(), graph.chain().compilerContractVersion());
    new ChainPlanGraphValidator(schemaService).validate(graph, CONTRACT, revision);
  }

  @Test
  void reusesConcreteIfBranchEntryInsteadOfGeneratingNestedIfShell() {
    ChainSemanticRevision revision = conditionRevisionWithConcreteIfEntry();

    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    assertEquals(1, graph.nodes().stream().filter(node -> "if".equals(node.type())).count());
    assertEquals("condition-1", node(graph, "existing-if").parentNodeId());
    assertEquals("existing-if", node(graph, "script-true").parentNodeId());
    assertEquals("status == 'ok'", property(node(graph, "existing-if"), "condition"));
    assertEquals("1", property(node(graph, "existing-if"), "priority"));
    assertEquals("condition-1", edge(graph, "edge-true").fromNodeId());
    assertEquals("existing-if", edge(graph, "edge-true").toNodeId());
    new ChainPlanGraphValidator(schemaService).validate(graph, CONTRACT, revision);
  }

  @Test
  void compilesAsyncApiTrigger() {
    ChainPlanGraph graph = compiler.compile(asyncApiTriggerRevision(), CONTRACT, List.of());

    assertEquals("async-api-trigger", node(graph, "trigger-async").type());
  }

  @Test
  void compilesChainTriggerWithoutCatalogBinding() {
    ChainSemanticRevision revision =
        revision(
            List.of(entry("chain-in", "trigger-chain", "op-shared")),
            List.of(
                new SemanticNode.Trigger(
                    "trigger-chain",
                    "chain-trigger-2",
                    new SemanticProvenance(List.of("fact-chain"))),
                new SemanticNode.Operation(
                    "op-shared", "script", new SemanticProvenance(List.of("fact-script")))),
            List.of(),
            List.of(sequence("edge-chain-in", "trigger-chain", "op-shared", null)),
            List.of(),
            List.of());

    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    assertEquals("chain-trigger-2", node(graph, "trigger-chain").type());
    assertTrue(new ChainPlanGraphValidator(schemaService).validate(graph).isEmpty());
  }

  @Test
  void compilesChainCallWithoutCatalogBinding() {
    ChainSemanticRevision revision =
        revision(
            List.of(entry("http-in", "trigger-http", "call-other")),
            List.of(
                new SemanticNode.Trigger(
                    "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "call-other", "chain-call-2", new SemanticProvenance(List.of()))),
            List.of(),
            List.of(sequence("edge-entry", "trigger-http", "call-other", null)),
            List.of(),
            List.of());

    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    assertEquals("chain-call-2", node(graph, "call-other").type());
    assertNull(property(node(graph, "call-other"), "elementId"));
    assertTrue(new ChainPlanGraphValidator(schemaService).validate(graph).isEmpty());
  }

  @Test
  void compilesStandaloneReuseContainerBesideMainFlow() {
    ChainSemanticRevision revision =
        revision(
            List.of(entry("http-in", "trigger-http", "reuse-reference")),
            List.of(
                new SemanticNode.Trigger(
                    "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "reuse-reference", "reuse-reference", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "after-reuse", "header-modification", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "reuse-container", "reuse", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "reuse-body", "header-modification", new SemanticProvenance(List.of()))),
            List.of(),
            List.of(
                sequence("edge-entry", "trigger-http", "reuse-reference", null),
                sequence("edge-after", "reuse-reference", "after-reuse", null)),
            List.of(new SemanticContainment("reuse-container", "reuse-body", "body")),
            List.of());

    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    assertEquals("reuse", node(graph, "reuse-container").type());
    assertEquals("reuse-container", node(graph, "reuse-body").parentNodeId());
    assertNull(property(node(graph, "reuse-reference"), "reuseElementId"));
    assertTrue(new ChainPlanGraphValidator(schemaService).validate(graph).isEmpty());
  }

  @Test
  void stampsKafkaCatalogBindingOntoAsyncApiTrigger() {
    ResolvedServiceCallBinding consume =
        kafkaBinding("trigger-async", "consume-om", "task.wfms_createWorkOrder.start", "wfms", "g-1");
    ChainPlanGraph graph =
        compiler.compile(asyncApiTriggerRevision(), CONTRACT, List.of(consume));

    ChainPlanNode trigger = node(graph, "trigger-async");
    assertEquals("async-api-trigger", trigger.type());
    assertEquals("op-om", property(trigger, "integrationOperationId"));
    assertEquals("task.wfms_createWorkOrder.start", property(trigger, "integrationOperationPath"));
    assertNull(property(trigger, "groupId"));
    assertEquals(
        "{\"maas.classifier.name\":\"wfms\",\"groupId\":\"g-1\"}",
        property(trigger, "integrationOperationAsyncProperties"));
    assertNull(property(trigger, "connectionSourceType"));
    assertNull(property(trigger, "brokers"));
    assertNull(property(trigger, "serviceCallId"));
  }

  @Test
  void compilesEveryEntryPointWithoutPickingTheFirst() {
    ChainPlanGraph graph = compiler.compile(twoEntryRevision(), CONTRACT, List.of());

    assertEquals(
        Set.of("trigger-http", "trigger-kafka", "op-shared"),
        graph.nodes().stream().map(ChainPlanNode::nodeId).collect(Collectors.toSet()));
    assertEquals(
        Set.of("edge-http-in", "edge-kafka-in"),
        graph.edges().stream().map(ChainPlanEdge::edgeId).collect(Collectors.toSet()));
    assertEquals("http-trigger", node(graph, "trigger-http").type());
    assertEquals("kafka-trigger-2", node(graph, "trigger-kafka").type());
  }

  @Test
  void addTimeSchemaDefaultsApplyToEveryCompiledNodeType() {
    ChainPlanGraph graph =
        compileMapped(linearMappedRevision(), List.of(binding("call-1")));

    assertEquals("NONE", property(node(graph, "trigger-http"), "accessControlType"));
    assertEquals("0", property(node(graph, "call-1"), "retryCount"));
    assertEquals("5000", property(node(graph, "call-1"), "retryDelay"));
  }

  @Test
  void compilesKafkaSenderWithSchemaDefaults() {
    ChainSemanticRevision revision =
        revision(
            List.of(entry("http-in", "trigger-http", "send-kafka")),
            List.of(
                new SemanticNode.Trigger(
                    "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "send-kafka", "kafka-sender-2", new SemanticProvenance(List.of()))),
            List.of(),
            List.of(sequence("edge-entry", "trigger-http", "send-kafka", null)),
            List.of(),
            List.of());

    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    ChainPlanNode sender = node(graph, "send-kafka");
    assertEquals("kafka-sender-2", sender.type());
    assertEquals("manual", property(sender, "connectionSourceType"));
    assertEquals("TLS", property(sender, "sslProtocol"));
    assertEquals(
        "org.apache.kafka.common.serialization.StringSerializer",
        property(sender, "keySerializer"));
    assertEquals(
        "org.apache.kafka.common.serialization.StringSerializer",
        property(sender, "valueSerializer"));
  }

  @Test
  void projectsImplementedServiceCatalogBindingOntoHttpTriggerWithoutContextPath() {
    CatalogBindingHint binding =
        new CatalogBindingHint(
            CatalogBindingHint.SCHEMA_VERSION,
            "http-in",
            "http-in",
            "GET /geo/{id}",
            "sys-geo",
            "sg-geo",
            "spec-geo",
            "op-geo",
            "http",
            "GET",
            "/geo/{id}",
            "v1",
            Instant.EPOCH,
            "catalog-read:sys-geo/spec-geo/op-geo");
    RequirementBrief brief =
        new RequirementBrief(
            "Geo",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            null,
            "",
            List.of(
                RequirementFactFixtures.implementedServiceHttpTriggerFact(
                    "http-in", "GeoSite", "getGeo")),
            List.of(
                new RequirementEntryPoint(
                    "http-in", "http-in", "http-trigger", "", "GET", "", "getGeo")),
            List.of(),
            List.of(),
            List.of(),
            new RequirementFlow(
                List.of(
                    new RequirementFlow.Interaction(
                        "http-in", RequirementFlow.Direction.INBOUND, "GeoSite", "getGeo", "")),
                List.of()),
            List.of(binding));

    ChainPlanGraph graph = compiler.compile(conditionRevision(), CONTRACT, List.of(), brief);

    ChainPlanNode trigger = node(graph, "trigger-http");
    assertEquals("op-geo", property(trigger, "integrationOperationId"));
    assertEquals("sys-geo", property(trigger, "integrationSystemId"));
    assertEquals("/geo/{id}", property(trigger, "integrationOperationPath"));
    assertEquals("GET", property(trigger, "httpMethodRestrict"));
    assertNull(property(trigger, "contextPath"));
  }

  @Test
  void projectsApprovedHttpEndpointPropertiesOntoTrigger() {
    RequirementBrief brief =
        new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            null,
            "",
            List.of(RequirementFactFixtures.httpTriggerFact("http-in", "POST", "/orders")),
            List.of(
                new RequirementEntryPoint(
                    "http-in", "http-in", "http-trigger", "", "POST", "/orders", "")),
            List.of(),
            List.of(),
            List.of(),
            new RequirementFlow(
                List.of(
                    new RequirementFlow.Interaction(
                        "http-in",
                        RequirementFlow.Direction.INBOUND,
                        "Caller",
                        "POST /orders",
                        "")),
                List.of()),
            List.of());

    ChainPlanGraph graph = compiler.compile(conditionRevision(), CONTRACT, List.of(), brief);

    ChainPlanNode trigger = node(graph, "trigger-http");
    assertEquals("/orders", property(trigger, "contextPath"));
    assertEquals("POST", property(trigger, "httpMethodRestrict"));
  }

  @Test
  void purePassThroughCompilesToDirectEdgeWithoutScriptOrMapper() {
    ChainPlanGraph graph =
        compiler.compile(
            SemanticFixtures.linearOrders(), CONTRACT, List.of(ordersCallBinding()));

    assertEquals(
        Set.of("trigger-http", "node-call"),
        graph.nodes().stream().map(ChainPlanNode::nodeId).collect(Collectors.toSet()));
    assertTrue(
        graph.nodes().stream()
            .noneMatch(n -> "script".equals(n.type()) || "mapper-2".equals(n.type())));
    assertEquals(1, graph.edges().size());
    assertEquals("trigger-http", graph.edges().getFirst().fromNodeId());
    assertEquals("node-call", graph.edges().getFirst().toNodeId());
  }

  @Test
  void completeTaskKeepsBehaviorOwnedScriptWithoutMappingIntentId() {
    ChainPlanGraph graph =
        compiler.compile(
            SemanticFixtures.linearOrdersWithCompleteTask(),
            CONTRACT,
            List.of(ordersCallBinding()));

    ChainPlanNode script = node(graph, SemanticFixtures.COMPLETE_TASK_NODE_ID);
    assertEquals("script", script.type());
    assertNull(property(script, MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY));
    assertTrue(
        graph.nodes().stream().noneMatch(n -> "mapper-2".equals(n.type())),
        "completeTask must not invent a mapper-2 node");
  }

  @Test
  void compilesOneBranchAsyncSplitWithRegionScope() {
    ChainSemanticRevision revision = asyncSplitOneBranchRevision();
    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of(binding("call-notify")));

    assertEquals("split-async-2", node(graph, "split-async-1").type());
    assertEquals("async-split-element-2", node(graph, "split-async-1-branch-notify").type());
    assertEquals("split-async-1", node(graph, "split-async-1-branch-notify").parentNodeId());
    assertEquals("split-async-1-branch-notify", node(graph, "call-notify").parentNodeId());
    assertEquals(0, node(graph, "split-async-1-branch-notify").order());
    assertEquals("split-async-1-branch-notify", edge(graph, "edge-notify").fromNodeId());
    assertEquals("split-async-1", edge(graph, "edge-notify").scopeNodeId());
    assertEquals(1, graph.nodes().stream().filter(n -> "call-notify".equals(n.nodeId())).count());
    new ChainPlanGraphValidator(schemaService).validate(graph, CONTRACT, revision);
  }

  @Test
  void compilesSynchronousSplitBranchShell() {
    ChainSemanticRevision revision = syncSplitOneBranchRevision();
    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    assertEquals("split-element-2", node(graph, "split-1-branch-work").type());
    assertEquals("split-1", node(graph, "split-1-branch-work").parentNodeId());
    assertEquals("split-1-branch-work", node(graph, "work-script").parentNodeId());
    assertEquals(0, node(graph, "split-1-branch-work").order());
    assertEquals("work", property(node(graph, "split-1-branch-work"), "splitName"));
    assertEquals("split-1-branch-work", edge(graph, "edge-work").fromNodeId());
    new ChainPlanGraphValidator(schemaService).validate(graph, CONTRACT, revision);
  }

  @Test
  void compilesParallelSplitBranchesWithoutSequentialSiblingEdges() {
    for (SplitMode mode : SplitMode.values()) {
      ChainSemanticRevision revision = splitTwoBranchRevision(mode);
      ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

      List<String> errors = new ChainPlanGraphValidator(schemaService).validate(graph);

      assertTrue(errors.isEmpty(), mode + ": " + String.join("; ", errors));
    }
  }

  @Test
  void keepsNestedRegionMembersUnderTheirOwnShells() {
    ChainSemanticRevision revision = nestedConditionInSplitRevision();
    ChainSemanticGraphCompiler uncheckedCompiler =
        new DefaultChainSemanticGraphCompiler((candidate, contract) -> {}, schemaService);

    ChainPlanGraph graph = uncheckedCompiler.compile(revision, CONTRACT, List.of());

    assertEquals("outer-split-branch-nested", node(graph, "inner-condition").parentNodeId());
    assertEquals("inner-condition-if-yes", node(graph, "yes-script").parentNodeId());
    assertEquals("inner-condition-else-no", node(graph, "no-script").parentNodeId());
    assertEquals("outer-split", edge(graph, "edge-yes-after").fromNodeId());
    assertEquals("outer-split", edge(graph, "edge-no-after").fromNodeId());
    assertNull(node(graph, "after-nested").parentNodeId());
    new ChainPlanGraphValidator(schemaService).validate(graph, CONTRACT, revision);
  }

  @Test
  void rejectsGeneratedShellIdCollision() {
    ChainSemanticRevision revision = nestedConditionInSplitRevision();
    List<SemanticNode> nodes = new java.util.ArrayList<>(revision.nodes());
    nodes.add(
        new SemanticNode.Operation(
            "outer-split-branch-nested", "script", new SemanticProvenance(List.of())));
    ChainSemanticRevision colliding =
        new ChainSemanticRevision(
            revision.schemaVersion(),
            revision.revisionId(),
            revision.chainIdentity(),
            revision.compilerContractVersion(),
            revision.entryPoints(),
            List.copyOf(nodes),
            revision.regions(),
            revision.executionEdges(),
            revision.containment(),
            revision.mappingIntents(),
            revision.constraints(),
            revision.assumptions(),
            revision.citations());
    ChainSemanticGraphCompiler uncheckedCompiler =
        new DefaultChainSemanticGraphCompiler((candidate, contract) -> {}, schemaService);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> uncheckedCompiler.compile(colliding, CONTRACT, List.of()));

    assertEquals(
        "Structural shell node id already exists: outer-split-branch-nested",
        error.getMessage());
  }

  @Test
  void compilesLoopScopeWithoutBackEdge() {
    ChainPlanGraph graph = compiler.compile(loopRevision(LoopMode.COPY), CONTRACT, List.of());

    assertEquals("loop-2", node(graph, "loop-1").type());
    assertEquals("items", property(node(graph, "loop-1"), "expression"));
    assertEquals("true", property(node(graph, "loop-1"), "copy"));
    assertNull(property(node(graph, "loop-1"), "doWhile"));
    assertEquals("loop-1", edge(graph, "edge-body").scopeNodeId());
    assertEquals("loop-1", edge(graph, "edge-exit").scopeNodeId());
    assertEquals("loop-1", edge(graph, "edge-exit").fromNodeId());
    assertEquals("loop-1", node(graph, "body-script").parentNodeId());
    assertNull(node(graph, "after-loop").parentNodeId());
    assertTrue(
        graph.edges().stream()
            .noneMatch(
                edge ->
                    "loop-1".equals(edge.toNodeId())
                        && !"trigger-http".equals(edge.fromNodeId())));
    new ChainPlanGraphValidator(schemaService).validate(graph, CONTRACT, loopRevision(LoopMode.COPY));
  }

  @Test
  void writesExclusiveDoWhileLoopMode() {
    ChainPlanGraph graph = compiler.compile(loopRevision(LoopMode.DO_WHILE), CONTRACT, List.of());

    assertEquals("true", property(node(graph, "loop-1"), "doWhile"));
    assertNull(property(node(graph, "loop-1"), "copy"));
  }

  @Test
  void writesRetryPropertiesOnTheOwner() {
    ChainPlanGraph graph =
        compiler.compile(retryRevision(), CONTRACT, List.of(binding("call-1")));

    ChainPlanNode owner = node(graph, "call-1");
    assertEquals("3", property(owner, "retryCount"));
    assertEquals("5000", property(owner, "retryDelay"));
    assertEquals("call-1", edge(graph, "edge-entry").scopeNodeId());
  }

  @Test
  void compilesErrorHandlersWithCatchContainment() {
    ChainPlanGraph graph = compiler.compile(errorScopeRevision(), CONTRACT, List.of());

    assertEquals("try-catch-finally-2", node(graph, "try-catch-1").type());
    assertEquals("try-2", node(graph, "try-catch-1-try").type());
    assertEquals("catch-2", node(graph, "try-catch-1-catch-catch-all").type());
    assertEquals("finally-2", node(graph, "try-catch-1-finally").type());
    assertEquals("try-catch-1-try", node(graph, "try-body").parentNodeId());
    assertEquals("try-catch-1-catch-catch-all", node(graph, "catch-body").parentNodeId());
    assertEquals("try-catch-1-finally", node(graph, "finally-script").parentNodeId());
    assertEquals(
        "java.lang.Exception",
        property(node(graph, "try-catch-1-catch-catch-all"), "exception"));
    assertEquals("0", property(node(graph, "try-catch-1-catch-catch-all"), "priority"));
    assertEquals("try-catch-1-try", edge(graph, "edge-try").fromNodeId());
    assertEquals("try-catch-1-catch-catch-all", edge(graph, "edge-catch").fromNodeId());
    assertEquals("try-catch-1-finally", edge(graph, "edge-finally").fromNodeId());
    assertEquals("try-catch-1", edge(graph, "edge-catch").scopeNodeId());
    assertTrue(new ChainPlanGraphValidator(schemaService).validate(graph).isEmpty());
  }

  @Test
  void projectsErrorScopeExitThroughTheWrapper() {
    ChainSemanticGraphCompiler uncheckedCompiler =
        new DefaultChainSemanticGraphCompiler((candidate, contract) -> {}, schemaService);
    ChainPlanGraph graph =
        uncheckedCompiler.compile(errorScopeRevisionWithContinuation(), CONTRACT, List.of());

    assertEquals("try-catch-1", edge(graph, "edge-after-error").fromNodeId());
    assertEquals("after-error", edge(graph, "edge-after-error").toNodeId());
  }

  @Test
  void validatesProjectedErrorScopeWithoutExplicitContainment() {
    ChainSemanticRevision revision = withoutContainment(errorScopeRevision());
    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    new ChainPlanGraphValidator(schemaService).validate(graph, CONTRACT, revision);
  }

  @Test
  void stampsExceptionAndPriorityWhenHandlerEntryIsCatch2() {
    ChainPlanGraph graph = compiler.compile(errorScopeRevision("catch-2"), CONTRACT, List.of());

    ChainPlanNode catchEntry = node(graph, "catch-body");
    assertEquals("catch-2", catchEntry.type());
    assertEquals("java.lang.Exception", property(catchEntry, "exception"));
    assertEquals("0", property(catchEntry, "priority"));
  }

  @Test
  void parentsExplicitCatchWithoutContainment() {
    ChainSemanticRevision revision =
        withoutContainment(withExplicitCatchScript(errorScopeRevision("catch-2")));
    ChainPlanGraph graph = compiler.compile(revision, CONTRACT, List.of());

    assertEquals("try-catch-1", node(graph, "catch-body").parentNodeId());
    assertEquals("catch-body", node(graph, "catch-script").parentNodeId());
    new ChainPlanGraphValidator(schemaService).validate(graph, CONTRACT, revision);
  }

  @Test
  void writesReservedIdentityOnServiceCallNodes() {
    ChainPlanGraph graph =
        compileMapped(linearMappedRevision(), List.of(binding("call-1")));

    ChainPlanNode call = node(graph, "call-1");
    assertEquals("call-1", call.serviceCallId().orElseThrow());
    assertEquals("call-1", call.semanticNodeId().orElseThrow());
    assertEquals("revision-1", call.semanticRevisionId().orElseThrow());
    assertEquals("INTEGRATION", property(call, "systemType"));
    assertEquals("sys-1", property(call, "integrationSystemId"));
    assertEquals("sg-1", property(call, "integrationSpecificationGroupId"));
    assertEquals("spec-1", property(call, "integrationSpecificationId"));
    assertEquals("http", property(call, "integrationOperationProtocolType"));
    assertEquals("op-call-1", property(call, "integrationOperationId"));
    assertEquals("GET", property(call, "integrationOperationMethod"));
    assertEquals("/orders/{id}", property(call, "integrationOperationPath"));
  }

  @Test
  void identityOverlayKeepsAddTimeServiceCallProperties() {
    ChainPlanGraph graph =
        compileMapped(linearMappedRevision(), List.of(binding("call-1")));

    ChainPlanNode call = node(graph, "call-1");
    assertEquals("0", property(call, "retryCount"));
    assertEquals("5000", property(call, "retryDelay"));
    assertEquals("call-1", call.semanticNodeId().orElseThrow());
    assertEquals("sys-1", property(call, "integrationSystemId"));
  }

  @Test
  void disablesServiceCallExceptionThrowingForInlineFailureResponses() {
    ChainSemanticRevision base = linearMappedRevision();
    List<SemanticNode> nodes = new java.util.ArrayList<>();
    for (SemanticNode node : base.nodes()) {
      if (node instanceof SemanticNode.ServiceCall call) {
        nodes.add(
            new SemanticNode.ServiceCall(
                call.nodeId(),
                call.serviceCallId(),
                call.operation(),
                ServiceCallFailureMode.INLINE_RESPONSE,
                call.provenance()));
      } else {
        nodes.add(node);
      }
    }
    ChainSemanticRevision inlineFailure =
        revision(
            base.entryPoints(),
            nodes,
            base.regions(),
            base.executionEdges(),
            base.containment(),
            base.mappingIntents());

    ChainPlanGraph graph = compileMapped(inlineFailure, List.of(binding("call-1")));

    assertEquals("false", property(node(graph, "call-1"), "errorThrowing"));
  }

  @Test
  void pinsMappingIdentityOnTheTransformSite() {
    ChainPlanGraph graph =
        compileMapped(linearMappedRevision(), List.of(binding("call-1")));

    assertEquals("map-body", MappingExecutionSite.mappingIntentId(node(graph, "op-shared")));
    assertEquals("map-body", MappingExecutionSite.mappingId(node(graph, "op-shared")));
    assertEquals("edge-call", MappingExecutionSite.semanticEdgeId(node(graph, "op-shared")));
    assertNull(MappingExecutionSite.mappingIntentId(node(graph, "call-1")));
  }

  @Test
  void compilesSiteOnlyRevisionWhenBriefHoldsMappingBodies() {
    ChainSemanticRevision revision =
        SemanticFixtures.withoutMappingBodies(linearMappedRevision());
    RequirementBrief brief =
        new RequirementBrief("Orders", List.of(), List.of(), List.of(), List.of(), "summary")
            .withMappingIntents(linearMappedRevision().mappingIntents());

    ChainPlanGraph graph =
        compiler.compile(revision, CONTRACT, List.of(binding("call-1")), brief);

    assertTrue(revision.mappingIntents().isEmpty());
    assertEquals("map-body", MappingExecutionSite.mappingIntentId(node(graph, "op-shared")));
  }

  @Test
  void rejectsMissingCatalogBinding() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> compileMapped(linearMappedRevision(), List.of()));
    assertEquals("missing catalog binding for serviceCallId=call-1", error.getMessage());
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing node " + nodeId));
  }

  private static ChainPlanEdge edge(ChainPlanGraph graph, String edgeId) {
    return graph.edges().stream()
        .filter(edge -> edgeId.equals(edge.edgeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing edge " + edgeId));
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (key.equals(property.key())) {
        return property.value();
      }
    }
    return null;
  }

  private static ResolvedServiceCallBinding ordersCallBinding() {
    return new ResolvedServiceCallBinding(
        "node-call",
        "call-1",
        "INTEGRATION",
        "sys-1",
        "sg-1",
        "spec-1",
        "op-call-1",
        "http",
        "GET",
        "/orders/{id}",
        "getOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-call-1",
        "");
  }

  private static ResolvedServiceCallBinding binding(String serviceCallId) {
    return new ResolvedServiceCallBinding(
        serviceCallId,
        serviceCallId,
        "INTEGRATION",
        "sys-1",
        "sg-1",
        "spec-1",
        "op-" + serviceCallId,
        "http",
        "GET",
        "/orders/{id}",
        "getOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-" + serviceCallId,
        "");
  }

  private static ResolvedServiceCallBinding kafkaBinding(
      String targetNodeId,
      String serviceCallId,
      String topic,
      String maasClassifierName,
      String groupId) {
    return new ResolvedServiceCallBinding(
        targetNodeId,
        serviceCallId,
        "INTERNAL",
        "sys-om",
        "sg-om",
        "spec-om",
        "op-om",
        "kafka",
        "subscribe",
        topic,
        "onTaskStart",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "catalog",
        "ev-om",
        "",
        maasClassifierName,
        groupId);
  }

  private static ChainSemanticRevision asyncApiTriggerRevision() {
    return revision(
        List.of(entry("async-in", "trigger-async", "op-shared")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-async", "async-api-trigger", new SemanticProvenance(List.of("fact-consume"))),
            new SemanticNode.Operation(
                "op-shared", "script", new SemanticProvenance(List.of("fact-script")))),
        List.of(),
        List.of(sequence("edge-async-in", "trigger-async", "op-shared", null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision conditionRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger("trigger-http", "http-trigger", new SemanticProvenance(List.of()));
    SemanticNode condition =
        new SemanticNode.Operation("condition-1", "condition", new SemanticProvenance(List.of()));
    SemanticNode trueBranch =
        new SemanticNode.Operation("script-true", "script", new SemanticProvenance(List.of()));
    SemanticNode falseBranch =
        new SemanticNode.Operation("script-false", "script", new SemanticProvenance(List.of()));
    SemanticNode join =
        new SemanticNode.Operation("script-common", "script", new SemanticProvenance(List.of()));
    return revision(
        List.of(entry("http-in", "trigger-http", "condition-1")),
        List.of(trigger, condition, trueBranch, falseBranch, join),
        List.of(
            new SemanticRegion.Condition(
                "region-condition",
                "condition-1",
                List.of(
                    new SemanticBranch.Condition(
                        "true-branch",
                        ConditionBranchRole.IF,
                        "status == 'ok'",
                        1,
                        "script-true",
                        List.of("script-true")),
                    new SemanticBranch.Condition(
                        "false-branch",
                        ConditionBranchRole.ELSE,
                        null,
                        0,
                        "script-false",
                        List.of("script-false"))),
                "script-common")),
        List.of(
            sequence("edge-entry", "trigger-http", "condition-1", null),
            new SemanticExecutionEdge(
                "edge-true",
                "condition-1",
                "script-true",
                "region-condition",
                new SemanticRoute.ConditionBranch("true-branch"),
                null),
            new SemanticExecutionEdge(
                "edge-false",
                "condition-1",
                "script-false",
                "region-condition",
                new SemanticRoute.ConditionBranch("false-branch"),
                null),
            new SemanticExecutionEdge(
                "edge-true-join",
                "script-true",
                "script-common",
                "region-condition",
                new SemanticRoute.Reconverge(List.of("true-branch")),
                null),
            new SemanticExecutionEdge(
                "edge-false-join",
                "script-false",
                "script-common",
                "region-condition",
                new SemanticRoute.Reconverge(List.of("false-branch")),
                null)),
        List.of(
            new SemanticContainment("condition-1", "script-true", "if"),
            new SemanticContainment("condition-1", "script-false", "else")),
        List.of());
  }

  private static ChainSemanticRevision conditionRevisionWithConcreteIfEntry() {
    ChainSemanticRevision base = withoutContainment(conditionRevision());
    List<SemanticNode> nodes = new ArrayList<>(base.nodes());
    nodes.add(
        new SemanticNode.Operation(
            "existing-if", "if", new SemanticProvenance(List.of())));
    SemanticRegion.Condition condition = (SemanticRegion.Condition) base.regions().getFirst();
    List<SemanticBranch.Condition> branches =
        condition.branches().stream()
            .map(
                branch ->
                    branch.role() == ConditionBranchRole.IF
                        ? new SemanticBranch.Condition(
                            branch.branchId(),
                            branch.role(),
                            branch.predicate(),
                            branch.priority(),
                            "existing-if",
                            branch.exitNodeIds())
                        : branch)
            .toList();
    List<SemanticExecutionEdge> edges = new ArrayList<>();
    for (SemanticExecutionEdge edge : base.executionEdges()) {
      if ("edge-true".equals(edge.edgeId())) {
        edges.add(
            new SemanticExecutionEdge(
                edge.edgeId(),
                edge.sourceNodeId(),
                "existing-if",
                edge.regionId(),
                edge.route(),
                edge.mappingId()));
      } else {
        edges.add(edge);
      }
    }
    edges.add(sequence("edge-if-body", "existing-if", "script-true", null));
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        base.entryPoints(),
        List.copyOf(nodes),
        List.of(
            new SemanticRegion.Condition(
                condition.regionId(),
                condition.ownerNodeId(),
                branches,
                condition.reconvergenceNodeId())),
        List.copyOf(edges),
        base.containment(),
        base.mappingIntents(),
        base.constraints(),
        base.assumptions(),
        base.citations());
  }

  private static ChainSemanticRevision twoEntryRevision() {
    return revision(
        List.of(
            SemanticFixtures.entry("http-in", "trigger-http"),
            SemanticFixtures.entry("kafka-in", "trigger-kafka")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Trigger(
                "trigger-kafka", "kafka-trigger-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "op-shared", "script", new SemanticProvenance(List.of("fact-shared")))),
        List.of(),
        List.of(
            sequence("edge-http-in", "trigger-http", "op-shared", null),
            sequence("edge-kafka-in", "trigger-kafka", "op-shared", null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision asyncSplitOneBranchRevision() {
    SemanticRegion.Split region = SemanticFixtures.asyncSplitOneBranch();
    return revision(
        List.of(entry("http-in", "trigger-http", "split-async-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "split-async-1", "split-async-2", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-notify", "call-notify", "notify", new SemanticProvenance(List.of()))),
        List.of(region),
        List.of(
            sequence("edge-entry", "trigger-http", "split-async-1", null),
            new SemanticExecutionEdge(
                "edge-notify",
                "split-async-1",
                "call-notify",
                region.regionId(),
                new SemanticRoute.SplitBranch("notify"),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision syncSplitOneBranchRevision() {
    SemanticRegion.Split region =
        new SemanticRegion.Split(
            "sync-split-region",
            "split-1",
            SplitMode.SYNC,
            List.of(new SemanticBranch.Split("work", 0, "work-script", List.of("work-script"))),
            "after-split");
    return revision(
        List.of(entry("http-in", "trigger-http", "split-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("split-1", "split-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "work-script", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "after-split", "script", new SemanticProvenance(List.of()))),
        List.of(region),
        List.of(
            sequence("edge-entry", "trigger-http", "split-1", null),
            new SemanticExecutionEdge(
                "edge-work",
                "split-1",
                "work-script",
                region.regionId(),
                new SemanticRoute.SplitBranch("work"),
                null),
            new SemanticExecutionEdge(
                "edge-after",
                "work-script",
                "after-split",
                region.regionId(),
                new SemanticRoute.Reconverge(List.of("work")),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision splitTwoBranchRevision(SplitMode mode) {
    SemanticRegion.Split region =
        new SemanticRegion.Split(
            "sync-split-region",
            "split-1",
            mode,
            List.of(
                new SemanticBranch.Split("audit", 0, "audit-script", List.of("audit-script")),
                new SemanticBranch.Split(
                    "fulfillment", 1, "fulfillment-script", List.of("fulfillment-script"))),
            null);
    return revision(
        List.of(entry("http-in", "trigger-http", "split-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "split-1",
                mode == SplitMode.SYNC ? "split-2" : "split-async-2",
                new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "audit-script", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "fulfillment-script", "script", new SemanticProvenance(List.of()))),
        List.of(region),
        List.of(
            sequence("edge-entry", "trigger-http", "split-1", null),
            new SemanticExecutionEdge(
                "edge-audit",
                "split-1",
                "audit-script",
                region.regionId(),
                new SemanticRoute.SplitBranch("audit"),
                null),
            new SemanticExecutionEdge(
                "edge-fulfillment",
                "split-1",
                "fulfillment-script",
                region.regionId(),
                new SemanticRoute.SplitBranch("fulfillment"),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision nestedConditionInSplitRevision() {
    SemanticRegion.Condition condition =
        new SemanticRegion.Condition(
            "inner-condition-region",
            "inner-condition",
            List.of(
                new SemanticBranch.Condition(
                    "yes",
                    ConditionBranchRole.IF,
                    "approved",
                    0,
                    "yes-script",
                    List.of("yes-script")),
                new SemanticBranch.Condition(
                    "no",
                    ConditionBranchRole.ELSE,
                    null,
                    1,
                    "no-script",
                    List.of("no-script"))),
            "after-nested");
    SemanticRegion.Split split =
        new SemanticRegion.Split(
            "outer-split-region",
            "outer-split",
            SplitMode.ASYNC,
            List.of(
                new SemanticBranch.Split(
                    "nested", 0, "inner-condition", List.of("inner-condition"))),
            "after-nested");
    return revision(
        List.of(entry("http-in", "trigger-http", "outer-split")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "outer-split", "split-async-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "inner-condition", "condition", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "yes-script", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "no-script", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "after-nested", "script", new SemanticProvenance(List.of()))),
        List.of(condition, split),
        List.of(
            sequence("edge-entry", "trigger-http", "outer-split", null),
            new SemanticExecutionEdge(
                "edge-nested",
                "outer-split",
                "inner-condition",
                split.regionId(),
                new SemanticRoute.SplitBranch("nested"),
                null),
            new SemanticExecutionEdge(
                "edge-yes",
                "inner-condition",
                "yes-script",
                condition.regionId(),
                new SemanticRoute.ConditionBranch("yes"),
                null),
            new SemanticExecutionEdge(
                "edge-no",
                "inner-condition",
                "no-script",
                condition.regionId(),
                new SemanticRoute.ConditionBranch("no"),
                null),
            new SemanticExecutionEdge(
                "edge-yes-after",
                "yes-script",
                "after-nested",
                condition.regionId(),
                new SemanticRoute.Reconverge(List.of("yes")),
                null),
            new SemanticExecutionEdge(
                "edge-no-after",
                "no-script",
                "after-nested",
                condition.regionId(),
                new SemanticRoute.Reconverge(List.of("no")),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision loopRevision(LoopMode mode) {
    return revision(
        List.of(entry("http-in", "trigger-http", "loop-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("loop-1", "loop-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("body-script", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("after-loop", "script", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticRegion.Loop(
                "loop-region",
                "loop-1",
                "body-script",
                List.of("body-script"),
                "after-loop",
                new LoopPolicy(mode, "items", 1500))),
        List.of(
            sequence("edge-entry", "trigger-http", "loop-1", null),
            new SemanticExecutionEdge(
                "edge-body",
                "loop-1",
                "body-script",
                "loop-region",
                new SemanticRoute.LoopBody(),
                null),
            new SemanticExecutionEdge(
                "edge-exit",
                "body-script",
                "after-loop",
                "loop-region",
                new SemanticRoute.LoopExit(),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision retryRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "call-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-1", "call-1", "getOrder", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("after-retry", "script", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticRegion.Retry(
                "retry-region",
                "call-1",
                "call-1",
                List.of("call-1"),
                "after-retry",
                new RetryPolicy(3, 5000))),
        List.of(
            new SemanticExecutionEdge(
                "edge-entry",
                "trigger-http",
                "call-1",
                "retry-region",
                new SemanticRoute.RetryAttempt(),
                null),
            new SemanticExecutionEdge(
                "edge-exhausted",
                "call-1",
                "after-retry",
                "retry-region",
                new SemanticRoute.RetryExhausted(),
                null)),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision errorScopeRevision() {
    return errorScopeRevision("script");
  }

  private static ChainSemanticRevision errorScopeRevision(String catchEntryElementType) {
    return revision(
        List.of(entry("http-in", "trigger-http", "try-catch-1")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "try-catch-1", "try-catch-finally-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("try-body", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "catch-body", catchEntryElementType, new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "finally-script", "script", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticRegion.ErrorScope(
                "error-region",
                "try-catch-1",
                "try-body",
                List.of(
                    new ErrorHandler(
                        "catch-all", "java.lang.Exception", "catch-body", List.of("catch-body"))),
                "finally-script",
                List.of("finally-script"))),
        List.of(
            sequence("edge-entry", "trigger-http", "try-catch-1", null),
            new SemanticExecutionEdge(
                "edge-try",
                "try-catch-1",
                "try-body",
                "error-region",
                new SemanticRoute.TryPath(),
                null),
            new SemanticExecutionEdge(
                "edge-catch",
                "try-catch-1",
                "catch-body",
                "error-region",
                new SemanticRoute.CatchPath("catch-all"),
                null),
            new SemanticExecutionEdge(
                "edge-finally",
                "try-catch-1",
                "finally-script",
                "error-region",
                new SemanticRoute.FinallyPath(),
                null)),
        List.of(
            new SemanticContainment("try-catch-1", "try-body", "try-2"),
            new SemanticContainment("try-catch-1", "catch-body", "catch-2"),
            new SemanticContainment("try-catch-1", "finally-script", "finally-2")),
        List.of());
  }

  private static ChainSemanticRevision errorScopeRevisionWithContinuation() {
    ChainSemanticRevision base = errorScopeRevision();
    List<SemanticNode> nodes = new ArrayList<>(base.nodes());
    nodes.add(
        new SemanticNode.Operation(
            "after-error", "script", new SemanticProvenance(List.of())));
    List<SemanticExecutionEdge> edges = new ArrayList<>(base.executionEdges());
    edges.add(sequence("edge-after-error", "finally-script", "after-error", "error-region"));
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        base.entryPoints(),
        List.copyOf(nodes),
        base.regions(),
        List.copyOf(edges),
        base.containment(),
        base.mappingIntents(),
        base.constraints(),
        base.assumptions(),
        base.citations());
  }

  private static ChainSemanticRevision withoutContainment(ChainSemanticRevision revision) {
    return new ChainSemanticRevision(
        revision.schemaVersion(),
        revision.revisionId(),
        revision.chainIdentity(),
        revision.compilerContractVersion(),
        revision.entryPoints(),
        revision.nodes(),
        revision.regions(),
        revision.executionEdges(),
        List.of(),
        revision.mappingIntents(),
        revision.constraints(),
        revision.assumptions(),
        revision.citations());
  }

  private static ChainSemanticRevision withExplicitCatchScript(ChainSemanticRevision revision) {
    List<SemanticNode> nodes = new java.util.ArrayList<>(revision.nodes());
    nodes.add(
        new SemanticNode.Operation("catch-script", "script", new SemanticProvenance(List.of())));
    SemanticRegion.ErrorScope scope = (SemanticRegion.ErrorScope) revision.regions().getFirst();
    SemanticRegion.ErrorScope updatedScope =
        new SemanticRegion.ErrorScope(
            scope.regionId(),
            scope.ownerNodeId(),
            scope.tryEntryNodeId(),
            List.of(
                new ErrorHandler(
                    "catch-all", "java.lang.Exception", "catch-body", List.of("catch-script"))),
            scope.finallyEntryNodeId(),
            scope.exitNodeIds());
    List<SemanticExecutionEdge> edges = new java.util.ArrayList<>(revision.executionEdges());
    edges.add(sequence("edge-catch-script", "catch-body", "catch-script", "error-region"));
    return new ChainSemanticRevision(
        revision.schemaVersion(),
        revision.revisionId(),
        revision.chainIdentity(),
        revision.compilerContractVersion(),
        revision.entryPoints(),
        List.copyOf(nodes),
        List.of(updatedScope),
        List.copyOf(edges),
        revision.containment(),
        revision.mappingIntents(),
        revision.constraints(),
        revision.assumptions(),
        revision.citations());
  }

  private ChainPlanGraph compileMapped(
      ChainSemanticRevision revision, List<ResolvedServiceCallBinding> bindings) {
    RequirementBrief brief =
        new RequirementBrief("Orders", List.of(), List.of(), List.of(), List.of(), "summary")
            .withMappingIntents(revision.mappingIntents());
    return compiler.compile(revision, CONTRACT, bindings, brief);
  }

  private static ChainSemanticRevision linearMappedRevision() {
    return revision(
        List.of(entry("http-in", "trigger-http", "op-shared")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation("op-shared", "script", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                "call-1", "call-1", "getOrder", new SemanticProvenance(List.of()))),
        List.of(),
        List.of(
            sequence("edge-entry", "trigger-http", "op-shared", null),
            new SemanticExecutionEdge(
                "edge-call",
                "op-shared",
                "call-1",
                null,
                new SemanticRoute.Sequence(),
                "map-body")),
        List.of(),
        List.of(
            new MappingIntent(
                "map-body",
                "edge-call",
                MappingPort.OUTPUT,
                "edge-call",
                MappingPort.REQUEST,
                List.of(new MappingIntentRule("id", "orderId", null)))));
  }

  private static SemanticEntryPoint entry(String id, String triggerNodeId, String targetNodeId) {
    return new SemanticEntryPoint(
        id, triggerNodeId, targetNodeId, 0, new SemanticProvenance(List.of()), null);
  }

  private static SemanticExecutionEdge sequence(
      String edgeId, String from, String to, String regionId) {
    return new SemanticExecutionEdge(
        edgeId, from, to, regionId, new SemanticRoute.Sequence(), null);
  }

  private static ChainSemanticRevision revision(
      List<SemanticEntryPoint> entryPoints,
      List<SemanticNode> nodes,
      List<SemanticRegion> regions,
      List<SemanticExecutionEdge> edges,
      List<SemanticContainment> containment,
      List<MappingIntent> mappings) {
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-1",
        "chain-greetings",
        CONTRACT.contractVersion(),
        entryPoints,
        nodes,
        regions,
        edges,
        containment,
        mappings,
        List.of(),
        List.of(),
        List.of());
  }
}
