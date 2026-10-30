package org.qubership.integration.platform.ai.productpipeline.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.serverlessworkflow.impl.WorkflowApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineCompatibilityAnalyzer;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReport;
import org.qubership.integration.platform.ai.compiler.pipeline.PipelineCompatibilityReportAugmenter;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.plan.MappingTurnInterpreter;
import org.qubership.integration.platform.ai.plan.MappingTurnTelemetry;
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.AutoUploadedSpecImportCapability;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.MappingRepairCaptureValidator;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementAnalysisCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.SpecificationImportCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlow;
import org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.create.flow.ProvidedIdsFlowTasks;
import org.qubership.integration.platform.ai.productpipeline.create.orchestration.CreateChainOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactSchemaRegistry;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineCompatibilityVerifier;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileCatalog;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryOutcomeTelemetry;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileKeys;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileValidator;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackRepository;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;
import org.qubership.integration.platform.ai.storage.S3Service;

/** CDI producers for the durable product-pipeline runtime graph. */
@ApplicationScoped
public class ProductPipelineRuntimeProducers {
  @jakarta.inject.Inject
  org.qubership.integration.platform.ai.llm.ratelimit.LlmRunBudget llmRunBudget;

  private static final String MATERIALIZATION_CAPABILITY_ID = "materialization";

  private static final Set<String> KNOWN_CAPABILITIES =
      Set.of(
          RequirementDiscoveryCapability.CAPABILITY_ID,
          SpecificationImportCapability.CAPABILITY_ID,
          AutoUploadedSpecImportCapability.CAPABILITY_ID,
          RequirementAnalysisCapability.CAPABILITY_ID,
          PlanningCapability.CAPABILITY_ID,
          DesignInputCapability.CAPABILITY_ID,
          DesignPlanningCapability.CAPABILITY_ID,
          DesignExecutionCapability.CAPABILITY_ID,
          MATERIALIZATION_CAPABILITY_ID);

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  @Inject
  public ProductPipelineRuntimeProducers() {}

  @Produces
  @ApplicationScoped
  ProductPipelineRunStore runStore(ArtifactBlobStore blobs, ObjectMapper mapper) {
    return new ProductPipelineRunStore(blobs, mapper, Clock.systemUTC());
  }

  @Produces
  @ApplicationScoped
  StageCapabilityRegistry capabilityRegistry(Instance<StageCapability> capabilities) {
    List<StageCapability> list = capabilities.stream().toList();
    if (list.isEmpty()) {
      throw new IllegalStateException("no StageCapability beans are registered");
    }
    return new StageCapabilityRegistry(list);
  }

  @Produces
  @ApplicationScoped
  ProductPipelineArtifactStore artifactStore(CompilationArtifacts artifacts) {
    return new ProductPipelineArtifactStore(artifacts);
  }

  @Produces
  @ApplicationScoped
  ProductPipelineProfileCatalog profileCatalog(CipKnowledgePackRepository packRepository) {
    ArtifactSchemaRegistry schemas = loadArtifactSchemas(packRepository);
    Map<String, ProductPipelineProfile> merged = new LinkedHashMap<>();
    for (String fileName : List.of("create-chain-v1.yaml", "create-chain-v2.yaml")) {
      ProductPipelineProfile bundled = loadBundledProfile(fileName);
      merged.put(profileKey(bundled), bundled);
    }
    for (String relativePath : packRepository.loadProductPipelineProfileResources()) {
      ProductPipelineProfile packProfile = loadPackProfile(packRepository, relativePath);
      merged.put(profileKey(packProfile), packProfile);
    }
    CompilerPipelineIndex activeIndex = packRepository.loadCompilerPipelineIndex();
    PipelineCompatibilityReport compatibilityReport =
        resolveCompatibilityReport(packRepository);
    compatibilityReport =
        PipelineCompatibilityReportAugmenter.augmentWithPackShippedProfiles(
            compatibilityReport,
            ProductPipelineProfileKeys.readFromPackRepository(packRepository),
            merged,
            activeIndex);
    ProductPipelineCompatibilityVerifier compatibilityVerifier =
        new ProductPipelineCompatibilityVerifier();
    List<ProductPipelineProfile> profiles = new ArrayList<>();
    for (ProductPipelineProfile profile : merged.values()) {
      ProductPipelineProfileValidator.validate(profile, schemas, KNOWN_CAPABILITIES);
      compatibilityVerifier.verify(profile, activeIndex, compatibilityReport);
      profiles.add(profile);
    }
    return new ProductPipelineProfileCatalog(profiles);
  }

  @Produces
  @ApplicationScoped
  ProductPipelineCompatibilityVerifier productPipelineCompatibilityVerifier() {
    return new ProductPipelineCompatibilityVerifier();
  }

  @Produces
  @ApplicationScoped
  CompilerRunPinResolver compilerRunPinResolver(
      CipKnowledgePackRepository packRepository, CompilerSkillDocumentService skillDocuments) {
    return new CompilerRunPinResolver(
        packRepository.loadCompilerPipelineIndex(),
        skillId -> {
          // These stages run in Java; pin their machine definitions rather than placeholder prompts.
          if (Set.of("cip-design-planner", "cip-design-executor").contains(skillId)) {
            String hash = packRepository.loadManifest().addonSha256().get(skillId);
            if (hash == null || hash.isBlank()) {
              throw new IllegalStateException("Missing Java stage definition: " + skillId);
            }
            return hash;
          }
          CompilerSkillDocument document = skillDocuments.loadByCapabilityId(skillId);
          return sha256Hex(document.markdown());
        });
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static PipelineCompatibilityReport resolveCompatibilityReport(
      CipKnowledgePackRepository packRepository) {
    try {
      return packRepository.loadPipelineCompatibilityReport();
    } catch (RuntimeException missingReport) {
      return new CompilerPipelineCompatibilityAnalyzer()
          .compare(null, packRepository.loadCompilerPipelineIndex());
    }
  }

  @Produces
  @ApplicationScoped
  FailureNarrative failureNarrative(FailureNarrativeAgent agent, AppConfig appConfig) {
    AppConfig.CreateConfig.FailureNarrativeConfig narrativeConfig = appConfig.create().failureNarrative();
    return new FailureNarrative(
        agent,
        narrativeConfig.maxCallsPerRun(),
        narrativeConfig.timeout(),
        appConfig.create().runCacheIdleTimeout());
  }

  @Produces
  @ApplicationScoped
  ProductPipelineRunSupport runSupport(
      ProductPipelineRunStore runs,
      ProductPipelineArtifactStore artifacts,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      ApprovalPromptAgent approvalPromptAgent,
      FailureNarrative failureNarrative,
      S3Service s3Service,
      AppConfig appConfig,
      RecoveryOutcomeTelemetry recoveryTelemetry,
      MappingTurnInterpreter mappingTurnInterpreter,
      MappingTurnTelemetry mappingTurnTelemetry,
      MappingRepairCaptureValidator mappingRepairCaptureValidator,
      ObjectMapper objectMapper,
      SchemaResourceLoader schemaResourceLoader,
      SchemaRefResolver schemaRefResolver,
      DeterministicElementSchemaService schemaService,
      org.qubership.integration.platform.ai.chat.conversation.ConversationService conversations,
      org.qubership.integration.platform.ai.plan.RequirementDraftStore requirementDrafts,
      org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore captureFeedback,
      org.qubership.integration.platform.ai.productpipeline.recovery.E2eRecoveryTestControl recoveryControl) {
    return new ProductPipelineRunSupport(
        runs,
        artifacts,
        capabilities,
        profileCatalog,
        compilerRunPinResolver,
        Clock.systemUTC(),
        new ApprovalPrompts(approvalPromptAgent),
            s3Service,
            failureNarrative,
            appConfig.create().runCacheIdleTimeout(),
            appConfig.create().repeatedFailureThreshold(),
            new RecoveryAttemptLedger(
                new RecoveryAttemptLedger.Limits(
                    appConfig.create().maxSemanticRepairs(),
                    appConfig.create().maxCausalReopens(),
                    appConfig.create().recoveryAttemptCeiling(),
                    appConfig.create().maxCleanRebuilds())),
            recoveryTelemetry,
            mappingTurnInterpreter,
            mappingTurnTelemetry,
            mappingRepairCaptureValidator,
            objectMapper,
            schemaResourceLoader,
            schemaRefResolver,
            schemaService).withConversations(conversations).withLlmBudget(llmRunBudget)
                .withNativeRequirementCapture(requirementDrafts, captureFeedback)
                .withRecoveryControl(recoveryControl);
  }

  @Produces
  @ApplicationScoped
  CreateChainOrchestrator createChainOrchestrator(
      ProductPipelineRunSupport runSupport,
      ProductPipelineRunStore runs,
      ProvidedIdsFlow flow,
      ProvidedIdsFlowTasks flowTasks,
      WorkflowApplication application) {
    return new ProvidedIdsFlowOrchestrator(runSupport, runs, flow, flowTasks, application);
  }

  private static ArtifactSchemaRegistry loadArtifactSchemas(
      CipKnowledgePackRepository packRepository) {
    try (InputStream packStream =
        packRepository.openProductPipelineResource("artifact-schemas.yaml")) {
      if (packStream != null) {
        try {
          return parseArtifactSchemas(packStream);
        } catch (IllegalStateException invalidPackSchemas) {
          // Test fixtures or partial packs may ship a placeholder; fall back to bundled schemas.
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read pack artifact schemas", e);
    }
    InputStream classpath =
        ProductPipelineRuntimeProducers.class.getResourceAsStream(
            "/product-pipelines/artifact-schemas.yaml");
    if (classpath != null) {
      try (classpath) {
        return parseArtifactSchemas(classpath);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot read classpath artifact schemas", e);
      }
    }
    throw new IllegalStateException(
        "artifact-schemas.yaml is missing from knowledge pack and classpath");
  }

  @SuppressWarnings("unchecked")
  private static ArtifactSchemaRegistry parseArtifactSchemas(InputStream in) throws IOException {
    Map<String, Object> root = YAML.readValue(in, Map.class);
    Object typesNode = root.get("types");
    if (!(typesNode instanceof List<?> types)) {
      throw new IllegalStateException("artifact-schemas.yaml must declare a types list");
    }
    List<ArtifactTypeRef> refs = new ArrayList<>();
    for (Object entry : types) {
      if (!(entry instanceof Map<?, ?> map)) {
        continue;
      }
      Object type = map.get("type");
      Object schemaVersion = map.get("schemaVersion");
      if (type == null || schemaVersion == null) {
        continue;
      }
      refs.add(new ArtifactTypeRef(String.valueOf(type), ((Number) schemaVersion).intValue()));
    }
    if (refs.isEmpty()) {
      throw new IllegalStateException("artifact-schemas.yaml declared no artifact types");
    }
    return new ArtifactSchemaRegistry(new LinkedHashSet<>(refs));
  }

  private static ProductPipelineProfile loadBundledProfile(String fileName) {
    InputStream classpath =
        ProductPipelineRuntimeProducers.class.getResourceAsStream(
            "/product-pipelines/profiles/" + fileName);
    if (classpath == null) {
      throw new IllegalStateException("bundled profile resource is missing: " + fileName);
    }
    try (classpath) {
      return ProductPipelineProfileParser.parse(classpath);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read bundled profile " + fileName, e);
    }
  }

  private static ProductPipelineProfile loadPackProfile(
      CipKnowledgePackRepository packRepository, String relativePath) {
    try (InputStream in = packRepository.openProductPipelineResource(relativePath)) {
      if (in == null) {
        throw new IllegalStateException(
            "pack profile resource is missing from knowledge index: " + relativePath);
      }
      return ProductPipelineProfileParser.parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read pack profile " + relativePath, e);
    }
  }

  private static String profileKey(ProductPipelineProfile profile) {
    return profile.profileId() + "@" + profile.profileVersion();
  }
}
