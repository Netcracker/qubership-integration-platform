package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackManifest;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class ChainSemanticCaptureToolTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  @AfterEach
  void unbind() {
    ProductCapabilityCaptureContext.unbind();
  }

  @Test
  void captureStoresOneCandidate() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(approvedBrief());
    String result = tool.captureChainSemanticRevision(linearRevision());
    assertTrue(result.contains("captured"), result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isPresent());
    assertEquals(
        linearRevision().revisionId(),
        ProductCapabilityCaptureContext.semanticCandidate().orElseThrow().revisionId());
  }

  @Test
  void duplicateCaptureFailsAndKeepsTheFirstCandidate() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(approvedBrief());
    tool.captureChainSemanticRevision(linearRevision());
    String duplicate = tool.captureChainSemanticRevision(linearRevision());
    assertTrue(duplicate.contains("already captured"), duplicate);
    assertEquals(1, ProductCapabilityCaptureContext.semanticCandidate().stream().count());
  }

  @Test
  void missingAddonFailsClosedWithoutACandidate() {
    ChainSemanticCaptureTool tool = tool(packMissingExecutor());
    bindDesign(approvedBrief());
    String result = tool.captureChainSemanticRevision(linearRevision());
    assertEquals("Required compiler addon is missing: cip-design-executor", result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isEmpty());
  }

  @Test
  void unknownEntryPointIsRejected() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(approvedBrief());
    ChainSemanticRevision revision = linearRevision();
    SemanticEntryPoint foreign =
        new SemanticEntryPoint(
            "foreign-entry",
            revision.entryPoints().getFirst().triggerNodeId(),
            revision.entryPoints().getFirst().initialTargetNodeId(),
            0,
            new SemanticProvenance(List.of("trigger-1")),
            null);
    ChainSemanticRevision mutated =
        new ChainSemanticRevision(
            revision.schemaVersion(),
            revision.revisionId(),
            revision.chainIdentity(),
            revision.compilerContractVersion(),
            List.of(foreign),
            revision.nodes(),
            revision.regions(),
            revision.executionEdges(),
            revision.containment(),
            revision.mappingIntents(),
            revision.constraints(),
            revision.assumptions(),
            revision.citations());
    String result = tool.captureChainSemanticRevision(mutated);
    assertTrue(result.contains("foreign-entry"), result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isEmpty());
  }

  @Test
  void unknownServiceCallIdIsRejected() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(approvedBrief());
    ChainSemanticRevision revision = linearRevision();
    List<SemanticNode> nodes =
        revision.nodes().stream()
            .map(
                node ->
                    node instanceof SemanticNode.ServiceCall call
                        ? new SemanticNode.ServiceCall(
                            call.nodeId(), "ghost-call", call.operation(), call.provenance())
                        : node)
            .toList();
    ChainSemanticRevision mutated =
        new ChainSemanticRevision(
            revision.schemaVersion(),
            revision.revisionId(),
            revision.chainIdentity(),
            revision.compilerContractVersion(),
            revision.entryPoints(),
            nodes,
            revision.regions(),
            revision.executionEdges(),
            revision.containment(),
            revision.mappingIntents(),
            revision.constraints(),
            revision.assumptions(),
            revision.citations());
    String result = tool.captureChainSemanticRevision(mutated);
    assertTrue(result.contains("ghost-call"), result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isEmpty());
  }

  private static void bindDesign(RequirementBrief brief) {
    ProductCapabilityCaptureContext.bindDesign("run-1", "conv-1", brief, payload -> {});
  }

  private static ChainSemanticCaptureTool tool(QipKnowledgePackRepository pack) {
    CatalogElementDescriptorLoader descriptors = mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(descriptors);
    return new ChainSemanticCaptureTool(
        new DefaultChainSemanticRevisionValidator(),
        new ClasspathCompilerContractRepository(),
        pack,
        descriptors);
  }

  private static QipKnowledgePackRepository completePack() {
    Map<String, String> addons = new LinkedHashMap<>();
    for (String addonId : CONTRACT.requiredAddons()) {
      addons.put(addonId, "sha-" + addonId);
    }
    Map<String, String> files = new LinkedHashMap<>();
    for (String fragment : CONTRACT.requiredKnowledgeFragments()) {
      files.put(fragment, "sha-" + fragment);
    }
    return pack(addons, files);
  }

  private static QipKnowledgePackRepository packMissingExecutor() {
    Map<String, String> addons = new LinkedHashMap<>();
    for (String addonId : CONTRACT.requiredAddons()) {
      if (!"cip-design-executor".equals(addonId)) {
        addons.put(addonId, "sha-" + addonId);
      }
    }
    Map<String, String> files = new LinkedHashMap<>();
    for (String fragment : CONTRACT.requiredKnowledgeFragments()) {
      files.put(fragment, "sha-" + fragment);
    }
    return pack(addons, files);
  }

  private static QipKnowledgePackRepository pack(
      Map<String, String> addons, Map<String, String> files) {
    QipKnowledgePackRepository repository = mock(QipKnowledgePackRepository.class);
    QipKnowledgePackManifest manifest =
        new QipKnowledgePackManifest(
            new QipKnowledgePackVersion("v1", "v1"),
            "test",
            Instant.parse("2026-01-01T00:00:00Z"),
            files,
            List.of(),
            List.of(),
            List.of(),
            CONTRACT.contractVersion(),
            CONTRACT.sha256(),
            addons);
    when(repository.loadManifest()).thenReturn(manifest);
    when(repository.activeVersion()).thenReturn(manifest.version());
    return repository;
  }

  private static RequirementBrief approvedBrief() {
    return new RequirementBrief(
        "Orders",
        List.of("HTTP POST /orders"),
        List.of(),
        List.of(),
        List.of(),
        "Create order",
        "draft-1",
        "draft",
        List.of(
            new RequirementFact(
                "trigger-1",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.ENDPOINT,
                "http-trigger",
                "HTTP POST /orders",
                "",
                "createOrder",
                "",
                "POST",
                "/orders",
                ""),
            new RequirementFact(
                "fact-call",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.SERVICE_CALL,
                "http-service-call",
                "Create an order via Orders API",
                "Orders API",
                "getOrder",
                "",
                "",
                "",
                "call-1")),
        List.of(),
        List.of(
            new RequirementEntryPoint(
                "http-in", "trigger-1", "http-trigger", "", "POST", "/orders", "createOrder")),
        List.of(new RequirementServiceCall("call-1", "fact-call", "Orders API", "getOrder")),
        List.of(),
        List.of());
  }

  private static ChainSemanticRevision linearRevision() {
    SemanticNode trigger =
        new SemanticNode.Trigger(
            "trigger-http", "http-trigger", new SemanticProvenance(List.of("trigger-1")));
    SemanticNode script =
        new SemanticNode.Operation("op-shared", "script", new SemanticProvenance(List.of()));
    SemanticNode call =
        new SemanticNode.ServiceCall(
            "node-call", "call-1", "getOrder", new SemanticProvenance(List.of("fact-call")));
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-1",
        "chain-greetings",
        CONTRACT.contractVersion(),
        List.of(
            new SemanticEntryPoint(
                "http-in",
                "trigger-http",
                "op-shared",
                0,
                new SemanticProvenance(List.of("trigger-1")),
                null)),
        List.of(trigger, script, call),
        List.of(),
        List.of(
            new SemanticExecutionEdge(
                "edge-entry",
                "trigger-http",
                "op-shared",
                null,
                new SemanticRoute.Sequence(),
                null),
            new SemanticExecutionEdge(
                "edge-call",
                "op-shared",
                "node-call",
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
                List.of(new MappingIntentRule("id", "orderId", null)))),
        List.of(),
        List.of(),
        List.of());
  }
}
