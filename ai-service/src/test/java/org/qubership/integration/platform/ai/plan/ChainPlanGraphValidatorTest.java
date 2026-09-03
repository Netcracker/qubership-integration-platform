package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ContainmentRole;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ElementContract;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptor;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticContainment;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

@ExtendWith(MockitoExtension.class)
class ChainPlanGraphValidatorTest {

  @Mock private DeterministicElementSchemaService schemaService;

  private ChainPlanGraphValidator validator;

  @BeforeEach
  void setUp() {
    validator = new ChainPlanGraphValidator(schemaService);
  }

  @Test
  void rejectsCatch2ExceptionTypeWithAllowedKeysHint() {
    when(schemaService.hasElementSchema("catch-2")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("catch-2"))
        .thenReturn(Set.of("exception", "priority", "script"));

    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("eh", "EH"),
                List.of(
                    new ChainPlanNode(
                        "catch-1",
                        "catch-2",
                        "Catch",
                        "eh",
                        null,
                        List.of(new PlanProperty("exceptionType", "java.lang.Exception")))),
                List.of()));

    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(error -> error.contains("unknown property key 'exceptionType'")));
    assertTrue(errors.stream().anyMatch(error -> error.contains("exception, not exceptionType")));
    assertTrue(errors.stream().anyMatch(error -> error.contains("Allowed keys: exception")));
  }

  @Test
  void rejectsExceptionOnScriptWithCatch2Hint() {
    when(schemaService.hasElementSchema("script")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("script"))
        .thenReturn(Set.of("script", "exportFileExtension"));

    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("greetings", "Greetings"),
                List.of(
                    new ChainPlanNode(
                        "script-1",
                        "script",
                        "Return",
                        null,
                        null,
                        List.of(new PlanProperty("exception", "java.lang.Exception")))),
                List.of()));

    assertFalse(errors.isEmpty());
    assertTrue(
        errors.stream().anyMatch(error -> error.contains("unknown property key 'exception'")));
    assertTrue(
        errors.stream()
            .anyMatch(error -> error.contains("Those keys belong on catch-2, not on script.")));
  }

  @Test
  void rejectsUnknownHttpTriggerPropertyKey() {
    when(schemaService.hasElementSchema("http-trigger")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("http-trigger"))
        .thenReturn(Set.of("contextPath", "httpMethodRestrict"));

    List<String> errors =
        validator.validate(
            graphWithHttpTriggerProperty(new PlanProperty("path", "/greetings")));

    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(error -> error.contains("unknown property key 'path'")));
    assertTrue(errors.stream().anyMatch(error -> error.contains("contextPath, not path")));
  }

  @Test
  void acceptsMissingElementPropertiesAtSkeletonCapture() {
    when(schemaService.hasElementSchema("http-trigger")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("http-trigger"))
        .thenReturn(Set.of("contextPath", "httpMethodRestrict", "externalRoute"));

    List<String> errors =
        validator.validate(
            graphWithHttpTriggerProperty(new PlanProperty("externalRoute", "true")));

    assertTrue(errors.isEmpty());
  }

  @Test
  void acceptsSchemaDefinedHttpTriggerKeys() {
    when(schemaService.hasElementSchema("http-trigger")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("http-trigger"))
        .thenReturn(Set.of("contextPath", "httpMethodRestrict"));

    List<String> errors =
        validator.validate(
            graphWithHttpTriggerProperty(
                new PlanProperty("contextPath", "/greetings"),
                new PlanProperty("httpMethodRestrict", "GET")));

    assertTrue(errors.isEmpty());
  }

  @Test
  void skipsPropertyKeyCheckWhenElementSchemaIsUnknown() {
    when(schemaService.hasElementSchema("custom-element")).thenReturn(false);
    when(schemaService.allowedPatchPropertyKeys("custom-element")).thenReturn(Set.of());

    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("demo", "Demo"),
                List.of(
                    new ChainPlanNode(
                        "n1",
                        "custom-element",
                        "Custom",
                        null,
                        null,
                        List.of(new PlanProperty("path", "/greetings")))),
                List.of()));

    assertTrue(errors.isEmpty());
  }

  @Test
  void rejectsPropertyOnKnownPropertylessElse() {
    when(schemaService.hasElementSchema("else")).thenReturn(true);
    when(schemaService.allowedPatchPropertyKeys("else")).thenReturn(Set.of());

    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("router", "Router"),
                List.of(
                    new ChainPlanNode(
                        "else-1",
                        "else",
                        "Else",
                        null,
                        null,
                        List.of(
                            new PlanProperty("condition", "preferredLang == 'en'"),
                            new PlanProperty("priority", "1")))),
                List.of()));

    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(error -> error.contains("unknown property key 'condition'")));
    assertTrue(errors.stream().anyMatch(error -> error.contains("unknown property key 'priority'")));
  }

  @Test
  void rejectsFlowNodeAtRootWhenEdgeFromTryCatchWrapper() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("fortune", "Fortune"),
                List.of(
                    new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                    new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                    new ChainPlanNode("parse", "script", "Parse", null, null, List.of())),
                List.of(
                    new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                        "e1", "tcff", "parse", null))));

    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(error -> error.contains("parentNodeId='try'")));
  }

  @Test
  void acceptsFlowNodeInsideTryWhenParentNodeIdSet() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("fortune", "Fortune"),
                List.of(
                    new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                    new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                    new ChainPlanNode("parse", "script", "Parse", "try", null, List.of())),
                List.of(
                    new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                        "e1", "try", "parse", null))));

    assertTrue(errors.isEmpty());
  }

  @Test
  void rejectsFlowNodeDirectlyInsideTryCatchWrapper() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("fortune", "Fortune"),
                List.of(
                    new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                    new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                    new ChainPlanNode("parse", "script", "Parse", "tcff", null, List.of())),
                List.of(
                    new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                        "e1", "try", "parse", null))));

    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(error -> error.contains("parentNodeId='try'")));
  }

  @Test
  void rejectsTryShellWithoutTryCatchParent() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("greetings", "Greetings"),
                List.of(
                    new ChainPlanNode("trigger", "http-trigger", "HTTP Trigger", null, null, List.of()),
                    new ChainPlanNode("try-2", "try-2", "Try", null, null, List.of()),
                    new ChainPlanNode("script-1", "script", "Return", null, null, List.of())),
                List.of(
                    new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                        "e1", "trigger", "try-2", null),
                    new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                        "e2", "try-2", "script-1", null))));

    assertFalse(errors.isEmpty());
    assertTrue(
        errors.stream()
            .anyMatch(error -> error.contains("must have parentNodeId of a try-catch-finally-2")));
  }

  @Test
  void acceptsTryShellInsideTryCatchParent() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("greetings", "Greetings"),
                List.of(
                    new ChainPlanNode(
                        "trigger",
                        "http-trigger",
                        "HTTP Trigger",
                        null,
                        null,
                        List.of(
                            new PlanProperty("contextPath", "/greetings"),
                            new PlanProperty("httpMethodRestrict", "GET"))),
                    new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                    new ChainPlanNode("try-2", "try-2", "Try", "tcff", null, List.of()),
                    new ChainPlanNode("script-1", "script", "Return", "try-2", null, List.of())),
                List.of(
                    new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                        "e1", "trigger", "tcff", null),
                    new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                        "e2", "try-2", "script-1", null))));

    assertTrue(errors.isEmpty());
  }

  @Test
  void effectiveParentNodeIdInfersTryChildFromWrapperEdge() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("fortune", "Fortune"),
            List.of(
                new ChainPlanNode("tcff", "try-catch-finally-2", "Try/Catch", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("parse", "script", "Parse", null, null, List.of())),
            List.of(
                new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge(
                    "e1", "tcff", "parse", null)));

    assertEquals(
        "try",
        ChainPlanGraphValidator.effectiveParentNodeId(
            graph.nodes().stream().filter(n -> "parse".equals(n.nodeId())).findFirst().orElseThrow(),
            graph));
  }

  @Test
  void acceptsConditionWithTwoDirectIfChildrenAndOptionalElse() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("lang-router", "Lang router"),
                List.of(
                    new ChainPlanNode("trigger", "http-trigger", "HTTP", null, null, List.of()),
                    new ChainPlanNode("route", "condition", "Route", null, null, List.of()),
                    new ChainPlanNode(
                        "if-en",
                        "if",
                        "English",
                        "route",
                        null,
                        List.of(
                            new PlanProperty("condition", "${header.lang} == 'en'"),
                            new PlanProperty("priority", "0"))),
                    new ChainPlanNode(
                        "if-fr",
                        "if",
                        "French",
                        "route",
                        null,
                        List.of(
                            new PlanProperty("condition", "${header.lang} == 'fr'"),
                            new PlanProperty("priority", "1"))),
                    new ChainPlanNode("else-1", "else", "Default", "route", null, List.of()),
                    new ChainPlanNode("script-en", "script", "EN", "if-en", null, List.of()),
                    new ChainPlanNode("script-fr", "script", "FR", "if-fr", null, List.of()),
                    new ChainPlanNode("script-other", "script", "Other", "else-1", null, List.of())),
                List.of(
                    new ChainPlanEdge("e1", "trigger", "route", null),
                    new ChainPlanEdge("e2", "if-en", "script-en", null),
                    new ChainPlanEdge("e3", "if-fr", "script-fr", null),
                    new ChainPlanEdge("e4", "else-1", "script-other", null))));

    assertTrue(errors.isEmpty(), String.join("; ", errors));
  }

  @Test
  void acceptsServiceCallSkeletonWithoutOperationBinding() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("pet-lookup", null),
                List.of(
                    new ChainPlanNode(
                        "sc1", "service-call", "Call", null, null, List.of())),
                List.of()));

    assertTrue(errors.isEmpty());
  }

  @Test
  void rejectsDanglingFlowSiblingsAtSameContainmentLevel() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("fortune", "Fortune"),
                List.of(
                    new ChainPlanNode("trigger", "http-trigger", "HTTP", null, null, List.of()),
                    new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                    new ChainPlanNode("parse", "script", "Parse", "try", null, List.of()),
                    new ChainPlanNode("route", "condition", "Route", "try", null, List.of()),
                    new ChainPlanNode("tcff", "try-catch-finally-2", "EH", null, null, List.of())),
                List.of(
                    new ChainPlanEdge("e1", "trigger", "tcff", null),
                    new ChainPlanEdge("e2", "try", "parse", null))));

    assertFalse(errors.isEmpty());
    assertTrue(
        errors.stream()
            .anyMatch(error -> error.contains("execution edge to another sibling")));
  }

  @Test
  void diagnosesMissingSiblingExecutionEdgeForRepair() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("fortune", "Fortune"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "HTTP", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                new ChainPlanNode("parse", "script", "Parse", "try", null, List.of()),
                new ChainPlanNode("route", "condition", "Route", "try", null, List.of()),
                new ChainPlanNode("tcff", "try-catch-finally-2", "EH", null, null, List.of())),
            List.of(
                new ChainPlanEdge("e1", "trigger", "tcff", null),
                new ChainPlanEdge("e2", "try", "parse", null)));

    List<ChainPlanRepairIssue> issues = validator.diagnoseForRepair(graph);

    assertFalse(issues.isEmpty());
    assertTrue(
        issues.stream()
            .anyMatch(
                issue ->
                    "MISSING_SIBLING_EXECUTION_EDGE".equals(issue.code())
                        && "route".equals(issue.nodeId())
                        && "try".equals(issue.parentNodeId())
                        && issue.siblingNodeIds().contains("parse")));
  }

  @Test
  void diagnosesBadEdgeReferenceForRepair() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("bad-edge", "Bad edge"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "HTTP", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", null, null, List.of())),
            List.of(new ChainPlanEdge("e1", "trigger", "missing", null)));

    List<ChainPlanRepairIssue> issues = validator.diagnoseForRepair(graph);

    assertEquals(1, issues.size());
    assertEquals("BAD_EDGE_REFERENCE", issues.get(0).code());
    assertEquals("e1", issues.get(0).edgeId());
    assertTrue(issues.get(0).invalidRefs().contains("toNodeId:missing"));
  }

  @Test
  void normalizesMissingSiblingEdgesInsideTryShell() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("safe-inventory", "Safe inventory"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "HTTP", null, null, List.of()),
                new ChainPlanNode("eh", "try-catch-finally-2", "EH", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "eh", null, List.of()),
                new ChainPlanNode("catch", "catch-2", "Catch", "eh", null, List.of()),
                new ChainPlanNode("service-call", "service-call", "Call", "try", null, List.of()),
                new ChainPlanNode("return-inventory", "script", "Return", "try", null, List.of()),
                new ChainPlanNode("return-error", "script", "Error", "catch", null, List.of())),
            List.of(
                new ChainPlanEdge("e1", "trigger", "eh", null),
                new ChainPlanEdge("e2", "try", "service-call", null)));

    ChainPlanGraph normalized = validator.normalizeMissingSiblingExecutionEdges(graph);

    assertTrue(validator.validate(normalized).isEmpty());
    assertTrue(
        normalized.edges().stream()
            .anyMatch(
                edge ->
                    "service-call".equals(edge.fromNodeId())
                        && "return-inventory".equals(edge.toNodeId())));
  }

  @Test
  void acceptsConnectedFlowSiblingsAtSameContainmentLevel() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("fortune", "Fortune"),
                List.of(
                    new ChainPlanNode("trigger", "http-trigger", "HTTP", null, null, List.of()),
                    new ChainPlanNode("try", "try-2", "Try", "tcff", null, List.of()),
                    new ChainPlanNode("parse", "script", "Parse", "try", null, List.of()),
                    new ChainPlanNode("route", "condition", "Route", "try", null, List.of()),
                    new ChainPlanNode("tcff", "try-catch-finally-2", "EH", null, null, List.of())),
                List.of(
                    new ChainPlanEdge("e1", "trigger", "tcff", null),
                    new ChainPlanEdge("e2", "try", "parse", null),
                    new ChainPlanEdge("e3", "parse", "route", null))));

    assertTrue(errors.isEmpty());
  }

  @Test
  void rejectsTriggerWithoutOutgoingFlowEdge() {
    List<String> errors =
        validator.validate(
            new ChainPlanGraph(
                "1.0",
                new ChainSection("secure-hello", null),
                List.of(
                    new ChainPlanNode(
                        "trigger", "http-trigger", "HTTP Trigger", null, null, List.of())),
                List.of()));

    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(error -> error.contains("must have an outgoing edge")));
  }

  @Test
  void rejectsDroppedSemanticEdge() {
    ChainSemanticRevision revision = conditionRevision();
    ChainPlanGraph compiledGraph = compiledConditionGraph();
    ChainPlanGraph missingEdge = removeEdge(compiledGraph, "edge-false-join");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> validator.validate(missingEdge, CONTRACT, revision));

    assertTrue(error.getMessage().contains("semantic edge edge-false-join is not represented"));
  }

  @Test
  void rejectsExecutionCycle() {
    ChainPlanGraph cyclic =
        replaceEdge(
            compiledConditionGraph(),
            "edge-true",
            new ChainPlanEdge("edge-true", "script-common", "script-true", "condition-1"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> validator.validate(cyclic, CONTRACT, conditionRevision()));

    assertTrue(error.getMessage().contains("execution cycle detected"));
  }

  @Test
  void rejectsWrongBranchCardinality() {
    CompilerContract strictIf = contractWithConditionIfMin(2);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> validator.validate(compiledConditionGraph(), strictIf, conditionRevision()));

    assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("cardinality"));
  }

  @Test
  void rejectsRuntimeDescriptorDrift() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    when(loader.load("http-trigger")).thenReturn(matchingDescriptor("http-trigger", false));
    when(loader.load("script")).thenReturn(matchingDescriptor("script", false));
    when(loader.load("condition")).thenReturn(matchingDescriptor("condition", false));
    ChainPlanGraphValidator driftValidator = new ChainPlanGraphValidator(schemaService, loader);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                driftValidator.validate(compiledConditionGraph(), CONTRACT, conditionRevision()));

    assertEquals(
        "Runtime descriptor is incompatible with compiler contract: condition container",
        error.getMessage());
  }

  @Test
  void rejectsMissingRuntimeDescriptor() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    when(loader.load(anyString()))
        .thenThrow(new CatalogElementDescriptorException("http-trigger", "not found."));
    ChainPlanGraphValidator missingValidator = new ChainPlanGraphValidator(schemaService, loader);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                missingValidator.validate(compiledConditionGraph(), CONTRACT, conditionRevision()));

    assertTrue(
        error.getMessage().contains("Required runtime descriptor is missing:"),
        error.getMessage());
  }

  @Test
  void acceptsCompiledConditionGraph() {
    validator.validate(compiledConditionGraph(), CONTRACT, conditionRevision());
  }

  private static ChainPlanGraph graphWithHttpTriggerProperty(PlanProperty... properties) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("greetings", "Greetings"),
        List.of(
            new ChainPlanNode(
                "n1", "http-trigger", "HTTP Trigger", null, null, List.of(properties)),
            new ChainPlanNode("script-1", "script", "Return", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "n1", "script-1", null)));
  }

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private static ChainPlanGraph compiledConditionGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection(
            "chain-greetings", null, null, null, "revision-1", CONTRACT.contractVersion()),
        List.of(
            new ChainPlanNode("trigger-http", "http-trigger", "trigger-http", null, null, List.of()),
            new ChainPlanNode("condition-1", "condition", "condition-1", null, null, List.of()),
            new ChainPlanNode("script-true", "script", "script-true", "condition-1", null, List.of()),
            new ChainPlanNode(
                "script-false", "script", "script-false", "condition-1", null, List.of()),
            new ChainPlanNode(
                "script-common", "script", "script-common", null, null, List.of())),
        List.of(
            new ChainPlanEdge("edge-entry", "trigger-http", "condition-1", null),
            new ChainPlanEdge("edge-true", "condition-1", "script-true", "condition-1"),
            new ChainPlanEdge("edge-false", "condition-1", "script-false", "condition-1"),
            new ChainPlanEdge("edge-true-join", "script-true", "script-common", "condition-1"),
            new ChainPlanEdge("edge-false-join", "script-false", "script-common", "condition-1")));
  }

  private static ChainPlanGraph removeEdge(ChainPlanGraph graph, String edgeId) {
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges()) {
      if (!edgeId.equals(edge.edgeId())) {
        edges.add(edge);
      }
    }
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), graph.nodes(), List.copyOf(edges));
  }

  private static ChainPlanGraph replaceEdge(
      ChainPlanGraph graph, String edgeId, ChainPlanEdge replacement) {
    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges()) {
      edges.add(edgeId.equals(edge.edgeId()) ? replacement : edge);
    }
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), graph.nodes(), List.copyOf(edges));
  }

  private static CompilerContract contractWithConditionIfMin(int min) {
    Map<String, ElementContract> elements = new LinkedHashMap<>(CONTRACT.elements());
    ElementContract condition = elements.get("condition");
    Map<String, ContainmentRole> roles = new LinkedHashMap<>(condition.containmentRoles());
    roles.put("if", new ContainmentRole(min, null));
    elements.put(
        "condition",
        new ElementContract(
            roles,
            condition.requiredProperties(),
            condition.materializationRuleId(),
            condition.runtimeDescriptor()));
    return new CompilerContract(
        CONTRACT.contractVersion(),
        CONTRACT.semanticSchemaVersion(),
        elements,
        CONTRACT.topology(),
        CONTRACT.requiredArtifacts(),
        CONTRACT.requiredAddons(),
        CONTRACT.requiredKnowledgeFragments(),
        CONTRACT.sha256());
  }

  private static CatalogElementDescriptor matchingDescriptor(String type, boolean container) {
    return new CatalogElementDescriptor(
        type, container, Map.of(), List.of(), false, "priority", false, false, false, true);
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
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-1",
        "chain-greetings",
        CONTRACT.contractVersion(),
        List.of(
            new SemanticEntryPoint(
                "http-in",
                "trigger-http",
                "condition-1",
                0,
                new SemanticProvenance(List.of()),
                null)),
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
            new SemanticExecutionEdge(
                "edge-entry", "trigger-http", "condition-1", null, new SemanticRoute.Sequence(), null),
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
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
