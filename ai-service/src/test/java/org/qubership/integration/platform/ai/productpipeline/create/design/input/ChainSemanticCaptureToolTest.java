package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedTrigger;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
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
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    String result = tool.captureChainSemanticRevision(ChainSemanticCaptureFixtures.linearCapture());
    assertTrue(result.contains("captured"), result);
    ChainSemanticRevision stored =
        ProductCapabilityCaptureContext.semanticCandidate().orElseThrow();
    assertTrue(stored.revisionId().startsWith("semantic-"), stored.revisionId());
    assertEquals(CONTRACT.contractVersion(), stored.compilerContractVersion());
  }

  @Test
  void captureFillsCatalogValuesTheModelNeverSends() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    tool.captureChainSemanticRevision(ChainSemanticCaptureFixtures.linearCapture());
    ChainSemanticRevision stored =
        ProductCapabilityCaptureContext.semanticCandidate().orElseThrow();
    assertEquals("http-trigger", node(stored, SemanticNode.Trigger.class).capabilityKey());
    assertEquals("getOrder", node(stored, SemanticNode.ServiceCall.class).operation());
  }

  private static <T extends SemanticNode> T node(ChainSemanticRevision revision, Class<T> type) {
    return revision.nodes().stream()
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow();
  }

  /**
   * LangChain4j runs a blocking tool on a worker thread that never called {@code bindDesign}. Only
   * the conversation id travels with it, so the binding must be reachable by that id.
   */
  @Test
  void captureSucceedsWhenTheToolRunsOnTheWorkerThreadInsteadOfTheBindingThread()
      throws Exception {
    ChainSemanticCaptureTool tool = tool(completePack());
    AtomicReference<Object> handedBack = new AtomicReference<>();
    ProductCapabilityCaptureContext.bindDesign(
        "run-1", "conv-1", ChainSemanticCaptureFixtures.approvedBrief(), handedBack::set);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      String result =
          worker
              .submit(
                  () -> {
                    ToolSession.bind("conv-1");
                    try {
                      return tool.captureChainSemanticRevision(
                          ChainSemanticCaptureFixtures.linearCapture());
                    } finally {
                      ToolSession.clear();
                    }
                  })
              .get();
      assertTrue(result.contains("captured"), result);
    } finally {
      worker.shutdownNow();
    }
    assertInstanceOf(ChainSemanticRevision.class, handedBack.get());
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isPresent());
  }

  /**
   * Requirement analysis binds on one thread and releases on another, so a pooled worker can still
   * carry its binding when design-input's tool lands there. The stale binding must not win.
   */
  @Test
  void captureIgnoresAnEarlierStageBindingLeftOnTheWorkerThread() throws Exception {
    ChainSemanticCaptureTool tool = tool(completePack());
    AtomicReference<Object> handedBack = new AtomicReference<>();
    ProductCapabilityCaptureContext.bindDesign(
        "run-1", "conv-1", ChainSemanticCaptureFixtures.approvedBrief(), handedBack::set);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      worker
          .submit(
              () ->
                  ProductCapabilityCaptureContext.bindAnalysis(
                      "run-0", "conv-0", null, payload -> {}))
          .get();
      String result =
          worker
              .submit(
                  () -> {
                    ToolSession.bind("conv-1");
                    try {
                      return tool.captureChainSemanticRevision(
                          ChainSemanticCaptureFixtures.linearCapture());
                    } finally {
                      ToolSession.clear();
                    }
                  })
              .get();
      assertTrue(result.contains("captured"), result);
    } finally {
      worker.shutdownNow();
    }
    assertInstanceOf(ChainSemanticRevision.class, handedBack.get());
  }

  @Test
  void captureIgnoresAnEarlierStageBindingForTheSameConversation() throws Exception {
    ChainSemanticCaptureTool tool = tool(completePack());
    AtomicReference<Object> handedBack = new AtomicReference<>();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      worker
          .submit(
              () ->
                  ProductCapabilityCaptureContext.bindAnalysis(
                      "run-0", "conv-1", null, payload -> {}))
          .get();
      ProductCapabilityCaptureContext.unbind("conv-1");
      ProductCapabilityCaptureContext.bindDesign(
          "run-1", "conv-1", ChainSemanticCaptureFixtures.approvedBrief(), handedBack::set);

      String result =
          worker
              .submit(
                  () -> {
                    ToolSession.bind("conv-1");
                    try {
                      return tool.captureChainSemanticRevision(
                          ChainSemanticCaptureFixtures.linearCapture());
                    } finally {
                      ToolSession.clear();
                    }
                  })
              .get();

      assertTrue(result.contains("captured"), result);
    } finally {
      worker.shutdownNow();
    }
    assertInstanceOf(ChainSemanticRevision.class, handedBack.get());
  }

  @Test
  void captureSucceedsWhenManifestUsesPackRelativePaths() {
    Map<String, String> files = packRelativeChecksums();
    assertTrue(files.containsKey("knowledge/ai/GENERATOR_CONTRACTS.md"));
    assertFalse(files.containsKey("generator-contracts"));
    ChainSemanticCaptureTool tool = tool(pack(completeAddons(), files));
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    String result = tool.captureChainSemanticRevision(ChainSemanticCaptureFixtures.linearCapture());
    assertTrue(result.contains("captured"), result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isPresent());
  }

  @Test
  void captureFailsWhenPackRelativePathSetIsMissingAFile() {
    Map<String, String> files = packRelativeChecksums();
    files.remove("knowledge/ai/GENERATOR_CONTRACTS.md");
    ChainSemanticCaptureTool tool = tool(pack(completeAddons(), files));
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    String result = tool.captureChainSemanticRevision(ChainSemanticCaptureFixtures.linearCapture());
    assertEquals("Required knowledge fragment is missing: generator-contracts", result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isEmpty());
  }

  @Test
  void foreignSourceFactIdIsRejected() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.linearCapture();
    ChainSemanticCapture mutated =
        new ChainSemanticCapture(
            capture.chainIdentity(),
            capture.entryPoints(),
            List.of(new CapturedTrigger("trigger-http", List.of("foreign-fact"))),
            capture.operations(),
            capture.sequenceRegions(),
            capture.conditionRegions(),
            capture.splitRegions(),
            capture.loopRegions(),
            capture.retryRegions(),
            capture.errorScopeRegions(),
            capture.edges(),
            capture.containment());
    String result = tool.captureChainSemanticRevision(mutated);
    assertTrue(result.contains("foreign-fact"), result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isEmpty());
  }

  @Test
  void duplicateCaptureFailsAndKeepsTheFirstCandidate() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    tool.captureChainSemanticRevision(ChainSemanticCaptureFixtures.linearCapture());
    String duplicate =
        tool.captureChainSemanticRevision(ChainSemanticCaptureFixtures.linearCapture());
    assertTrue(duplicate.contains("already captured"), duplicate);
    assertEquals(1, ProductCapabilityCaptureContext.semanticCandidate().stream().count());
  }

  @Test
  void missingAddonFailsClosedWithoutACandidate() {
    ChainSemanticCaptureTool tool = tool(packMissingExecutor());
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    String result = tool.captureChainSemanticRevision(ChainSemanticCaptureFixtures.linearCapture());
    assertEquals("Required compiler addon is missing: cip-design-executor", result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isEmpty());
  }

  @Test
  void omittedEntryPointsStillCapture() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    ChainSemanticCapture mutated =
        withEntryPoints(ChainSemanticCaptureFixtures.linearCapture(), List.of());
    String result = tool.captureChainSemanticRevision(mutated);
    assertTrue(result.contains("captured"), result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isPresent());
  }

  @Test
  void anEdgeToAnUnknownServiceCallNodeIsRejected() {
    ChainSemanticCaptureTool tool = tool(completePack());
    bindDesign(ChainSemanticCaptureFixtures.approvedBrief());
    ChainSemanticCapture capture = ChainSemanticCaptureFixtures.linearCapture();
    ChainSemanticCapture mutated =
        withEdges(
            capture,
            List.of(
                new ChainSemanticCapture.CapturedEdge(
                    "trigger-http", "op-shared", null, null, null, null, null, null),
                new ChainSemanticCapture.CapturedEdge(
                    "op-shared", "ghost-call", null, null, null, null, null, null)));
    String result = tool.captureChainSemanticRevision(mutated);
    assertTrue(result.contains("ghost-call"), result);
    assertTrue(ProductCapabilityCaptureContext.semanticCandidate().isEmpty());
  }

  private static ChainSemanticCapture withEdges(
      ChainSemanticCapture capture, List<ChainSemanticCapture.CapturedEdge> edges) {
    return new ChainSemanticCapture(
        capture.chainIdentity(),
        capture.entryPoints(),
        capture.triggers(),
        capture.operations(),
        capture.sequenceRegions(),
        capture.conditionRegions(),
        capture.splitRegions(),
        capture.loopRegions(),
        capture.retryRegions(),
        capture.errorScopeRegions(),
        edges,
        capture.containment());
  }

  private static ChainSemanticCapture withEntryPoints(
      ChainSemanticCapture capture, List<CapturedEntryPoint> entryPoints) {
    return new ChainSemanticCapture(
        capture.chainIdentity(),
        entryPoints,
        capture.triggers(),
        capture.operations(),
        capture.sequenceRegions(),
        capture.conditionRegions(),
        capture.splitRegions(),
        capture.loopRegions(),
        capture.retryRegions(),
        capture.errorScopeRegions(),
        capture.edges(),
        capture.containment());
  }

  private static void bindDesign(RequirementBrief brief) {
    ProductCapabilityCaptureContext.bindDesign("run-1", "conv-1", brief, payload -> {});
  }

  static ChainSemanticCaptureTool tool(QipKnowledgePackRepository pack) {
    CatalogElementDescriptorLoader descriptors = mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(descriptors);
    return new ChainSemanticCaptureTool(
        new ChainSemanticCaptureAdapter(new ChainSemanticCanonicalizer()),
        new DefaultChainSemanticRevisionValidator(),
        new ClasspathCompilerContractRepository(),
        pack,
        descriptors);
  }

  static QipKnowledgePackRepository completePack() {
    return pack(completeAddons(), packRelativeChecksums());
  }

  private static QipKnowledgePackRepository packMissingExecutor() {
    Map<String, String> addons = new LinkedHashMap<>();
    for (String addonId : CONTRACT.requiredAddons()) {
      if (!"cip-design-executor".equals(addonId)) {
        addons.put(addonId, "sha-" + addonId);
      }
    }
    return pack(addons, packRelativeChecksums());
  }

  static Map<String, String> completeAddons() {
    Map<String, String> addons = new LinkedHashMap<>();
    for (String addonId : CONTRACT.requiredAddons()) {
      addons.put(addonId, "sha-" + addonId);
    }
    return addons;
  }

  static Map<String, String> packRelativeChecksums() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("knowledge/ai/validation-rules.yaml", "sha-validation-rules");
    files.put("knowledge/ai/GENERATOR_CONTRACTS.md", "sha-generator-contracts");
    files.put("knowledge/ai/generator-rule-mapping.md", "sha-generator-rule-mapping");
    return files;
  }

  static QipKnowledgePackRepository pack(Map<String, String> addons, Map<String, String> files) {
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
}
