package org.qubership.integration.platform.ai.a2a.e2e;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunBindingStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementAnalysisCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.SpecificationImportCapability;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;
import org.qubership.integration.platform.ai.productpipeline.knowledge.FakeKnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationCapability;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;

/**
 * Builds a real {@link CreateChainApplicationFacade} + {@link CreateChainTestOrchestrator} with
 * deterministic stage adapters at capability boundaries (no Mockito facade/runtime mocks).
 */
final class A2aRealRuntimeFacadeFactory {

  private A2aRealRuntimeFacadeFactory() {}

  static Harness providedIdsPath() {
    return create(false, true);
  }

  static Harness generatedDesignPath() {
    return create(true, true);
  }

  private static Harness create(boolean needInputFirst, boolean materialize) {
    try {
      ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
      ProductPipelineProfile profileV1;
      ProductPipelineProfile profileV2;
      try (InputStream in =
          A2aRealRuntimeFacadeFactory.class.getResourceAsStream(
              "/product-pipelines/profiles/create-chain-v1.yaml")) {
        profileV1 = ProductPipelineProfileParser.parse(in);
      }
      try (InputStream in =
          A2aRealRuntimeFacadeFactory.class.getResourceAsStream(
              "/product-pipelines/profiles/create-chain-v2.yaml")) {
        profileV2 = ProductPipelineProfileParser.parse(in);
      }
      InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
      FakeKnowledgeClient knowledge = FakeKnowledgeClient.defaultFixture();
      ProductPipelineProfileCatalog catalog =
          new ProductPipelineProfileCatalog(List.of(profileV1, profileV2));
      Clock clock = Clock.fixed(Instant.parse("2026-08-04T06:00:00Z"), ZoneOffset.UTC);
      CreateRunBindingStore bindingStore = new CreateRunBindingStore(blobs, mapper);
      // Pin create-chain@1 so deterministic discovery/planning stubs match the profile graph.
      CreateRunSelectionService selectionService =
          new CreateRunSelectionService(
              "2026.1", knowledge, bindingStore, catalog, stubPinResolver(), clock, "1");
      CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, clock);
      ProductPipelineArtifactStore artifactStore = new ProductPipelineArtifactStore(artifacts);
      ProductPipelineRunStore runStore = new ProductPipelineRunStore(blobs, mapper, clock);
      StageCapabilityRegistry capabilities =
          new StageCapabilityRegistry(
              List.of(
                  discovery(needInputFirst),
                  importStage(),
                  analysis(),
                  planning(mapper),
                  materialization(materialize)));
      CreateChainTestOrchestrator runtime =
          new CreateChainTestOrchestrator(
              ProductPipelineRunSupport.builder(runStore, artifactStore, capabilities, clock)
                  .profileCatalog(catalog)
                  .compilerRunPinResolver(stubPinResolver())
                  .build(),
              runStore);
      CreateChainApplicationFacade facade =
          new CreateChainApplicationFacade(
              selectionService, bindingStore, runtime, runStore, catalog, artifactStore);
      return new Harness(facade, runtime, runStore, bindingStore);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to build real-runtime A2A facade harness", e);
    }
  }

  record Harness(
      CreateChainApplicationFacade facade,
      CreateChainTestOrchestrator runtime,
      ProductPipelineRunStore runStore,
      CreateRunBindingStore bindingStore) {}

  private static StageCapability discovery(boolean needInputFirst) {
    AtomicInteger calls = new AtomicInteger();
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementDiscoveryCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        if (needInputFirst && calls.incrementAndGet() == 1) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need input")));
        }
        if (!needInputFirst
            && calls.incrementAndGet() == 1
            && context.attributeAsString("userText") == null) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need input")));
        }
        RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                        "draft ready",
                        null)));
      }
    };
  }

  private static StageCapability importStage() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return SpecificationImportCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        RequirementDraft draft = RequirementFactFixtures.greetingsApprovedDraft();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_DRAFT, draft, List.of())),
                        "import skipped",
                        null)));
      }
    };
  }

  private static StageCapability analysis() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return RequirementAnalysisCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        RequirementBrief brief =
            new RequirementBrief("brief", List.of("fact"), List.of(), List.of(), List.of(), "ok");
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, brief, List.of())),
                        "analyzed",
                        null)));
      }
    };
  }

  private static StageCapability planning(ObjectMapper mapper) {
    var graph =
        new org.qubership.integration.platform.ai.plan.model.ChainPlanGraph(
            "1.0",
            new org.qubership.integration.platform.ai.plan.model.ChainSection("g", "G"),
            List.of(
                new org.qubership.integration.platform.ai.plan.model.ChainPlanNode(
                    "http-trigger", "http-trigger-2", "HTTP", null, null, List.of())),
            List.of());
    String graphDigest = new CanonicalGraphDigest(mapper).sha256(graph);
    var assembly =
        new org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult(
            1, graph, graphDigest, List.of(), List.of(), List.of());
    var bundle =
        new org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle(
            1,
            graphDigest,
            List.of(
                new org.qubership.integration.platform.ai.productpipeline.artifact
                    .CompilerValidationPass(
                    "validator",
                    new org.qubership.integration.platform.ai.qipknowledge.validation
                        .ValidationResult(true, List.of(), "ok"))));
    ImplementationPlan plan =
        ImplementationPlan.schemaVersion2(
            "Plan",
            "planning",
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    var validation =
        new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult(
            List.of());
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return PlanningCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(
                                Kind.IMPLEMENTATION_PLAN, plan, context.inputRefs()),
                            new ArtifactCandidate(
                                Kind.PLAN_VALIDATION_RESULT, validation, context.inputRefs()),
                            new ArtifactCandidate(Kind.CHAIN_PLAN_GRAPH, graph, context.inputRefs()),
                            new ArtifactCandidate(
                                Kind.GRAPH_ASSEMBLY_RESULT, assembly, context.inputRefs()),
                            new ArtifactCandidate(
                                Kind.COMPILER_VALIDATION_BUNDLE, bundle, context.inputRefs())),
                        "plan ready",
                        null)));
      }
    };
  }

  private static StageCapability materialization(boolean materialize) {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return MaterializationCapability.CAPABILITY_ID;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        if (!materialize) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.NEEDS_INPUT, "materialization stub")));
        }
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(
                            new ArtifactCandidate(
                                Kind.MATERIALIZATION_RESULT, Map.of("ok", true), List.of()),
                            new ArtifactCandidate(
                                Kind.CATALOG_CHAIN_SNAPSHOT,
                                new ChainCatalogFacts(
                                    "catalog-chain-a2a-1",
                                    "A2aRealRuntimeChain",
                                    "",
                                    2,
                                    0,
                                    "",
                                    List.of(),
                                    List.of(),
                                    "built_in_catalog"),
                                List.of()),
                            new ArtifactCandidate(
                                Kind.RECONCILE_RESULT, Map.of("reconcile", "ok"), List.of())),
                        "materialized",
                        null)));
      }
    };
  }

  private static CompilerRunPinResolver stubPinResolver() {
    CompilerRunPin pin =
        new CompilerRunPin(
            "pkg",
            "1",
            "digest",
            1,
            "idx-1",
            "idx-digest",
            new ResolvedCompilerDag(List.of(), List.of(), "dag"),
            List.of("planning"),
            Map.of(),
            Map.of("skill", "a".repeat(64)),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    CompilerRunPinResolver resolver = mock(CompilerRunPinResolver.class);
    when(resolver.resolve(any(), any())).thenReturn(pin);
    return resolver;
  }
}
