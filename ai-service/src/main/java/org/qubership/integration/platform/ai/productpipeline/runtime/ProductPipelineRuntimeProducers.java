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
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.llm.agent.ApprovalPromptAgent;
import org.qubership.integration.platform.ai.llm.agent.DesignInputPromptAgent;
import org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.ApprovalPrompts;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementAnalysisCapability;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementDiscoveryCapability;
import org.qubership.integration.platform.ai.productpipeline.create.SpecificationImportCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputCapability;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.DesignInputIdsPathPrompts;
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
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileValidator;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.storage.S3Service;

/** CDI producers for the durable product-pipeline runtime graph. */
@ApplicationScoped
public class ProductPipelineRuntimeProducers {

  private static final String MATERIALIZATION_CAPABILITY_ID = "materialization";

  private static final Set<String> KNOWN_CAPABILITIES =
      Set.of(
          RequirementDiscoveryCapability.CAPABILITY_ID,
          SpecificationImportCapability.CAPABILITY_ID,
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
  ProductPipelineProfileCatalog profileCatalog(QipKnowledgePackRepository packRepository) {
    ArtifactSchemaRegistry schemas = loadArtifactSchemas();
    ProductPipelineProfile createChainV1 = loadProfile("create-chain-v1.yaml");
    ProductPipelineProfile createChainV2 = loadProfile("create-chain-v2.yaml");
    ProductPipelineProfileValidator.validate(createChainV1, schemas, KNOWN_CAPABILITIES);
    ProductPipelineProfileValidator.validate(createChainV2, schemas, KNOWN_CAPABILITIES);
    verifyPipelineCompatibility(createChainV1, packRepository);
    verifyPipelineCompatibility(createChainV2, packRepository);
    return new ProductPipelineProfileCatalog(List.of(createChainV1, createChainV2));
  }

  @Produces
  @ApplicationScoped
  ProductPipelineCompatibilityVerifier productPipelineCompatibilityVerifier() {
    return new ProductPipelineCompatibilityVerifier();
  }

  @Produces
  @ApplicationScoped
  CompilerRunPinResolver compilerRunPinResolver(
      QipKnowledgePackRepository packRepository, CompilerSkillDocumentService skillDocuments) {
    return new CompilerRunPinResolver(
        packRepository.loadCompilerPipelineIndex(),
        skillId -> {
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

  private static void verifyPipelineCompatibility(
      ProductPipelineProfile profile, QipKnowledgePackRepository packRepository) {
    CompilerPipelineIndex activeIndex = packRepository.loadCompilerPipelineIndex();
    PipelineCompatibilityReport report;
    try {
      report = packRepository.loadPipelineCompatibilityReport();
    } catch (RuntimeException missingReport) {
      report = new CompilerPipelineCompatibilityAnalyzer().compare(null, activeIndex);
    }
    new ProductPipelineCompatibilityVerifier().verify(profile, activeIndex, report);
  }

  @Produces
  @ApplicationScoped
  ProductPipelineRunSupport runSupport(
      ProductPipelineRunStore runs,
      ProductPipelineArtifactStore artifacts,
      StageCapabilityRegistry capabilities,
      ProductPipelineProfileCatalog profileCatalog,
      CompilerRunPinResolver compilerRunPinResolver,
      DesignInputPromptAgent designInputPromptAgent,
      ApprovalPromptAgent approvalPromptAgent,
      FailureNarrativeAgent failureNarrativeAgent,
      S3Service s3Service,
      AppConfig appConfig) {
    AppConfig.CreateConfig.FailureNarrativeConfig narrativeConfig =
        appConfig.create().failureNarrative();
    return new ProductPipelineRunSupport(
        runs,
        artifacts,
        capabilities,
        profileCatalog,
        compilerRunPinResolver,
        Clock.systemUTC(),
        new DesignInputIdsPathPrompts(designInputPromptAgent),
        new ApprovalPrompts(approvalPromptAgent),
            s3Service,
            new FailureNarrative(
                failureNarrativeAgent,
                narrativeConfig.maxCallsPerRun(),
                narrativeConfig.timeout(),
                appConfig.create().runCacheIdleTimeout()),
            appConfig.create().runCacheIdleTimeout(),
            appConfig.create().repeatedFailureThreshold());
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

  private static ArtifactSchemaRegistry loadArtifactSchemas() {
    Path[] candidates =
        new Path[] {
          Path.of("integration-platform-skills/product-pipelines/artifact-schemas.yaml"),
          Path.of("../integration-platform-skills/product-pipelines/artifact-schemas.yaml"),
          Path.of(
              "ai-service/../integration-platform-skills/product-pipelines/artifact-schemas.yaml")
        };
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        try (InputStream in = Files.newInputStream(candidate)) {
          return parseArtifactSchemas(in);
        } catch (IOException e) {
          throw new UncheckedIOException("cannot read artifact schemas: " + candidate, e);
        }
      }
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
        "artifact-schemas.yaml is missing from filesystem and classpath");
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

  private static ProductPipelineProfile loadProfile(String fileName) {
    Path[] candidates =
        new Path[] {
          Path.of("integration-platform-skills/product-pipelines/profiles/" + fileName),
          Path.of("../integration-platform-skills/product-pipelines/profiles/" + fileName),
          Path.of(
              "ai-service/../integration-platform-skills/product-pipelines/profiles/" + fileName)
        };
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        try (InputStream in = Files.newInputStream(candidate)) {
          return ProductPipelineProfileParser.parse(in);
        } catch (IOException e) {
          throw new UncheckedIOException("cannot read profile: " + candidate, e);
        }
      }
    }
    InputStream classpath =
        ProductPipelineRuntimeProducers.class.getResourceAsStream(
            "/product-pipelines/profiles/" + fileName);
    if (classpath != null) {
      try (classpath) {
        return ProductPipelineProfileParser.parse(classpath);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot read classpath profile " + fileName, e);
      }
    }
    throw new IllegalStateException(
        fileName + " profile resource is missing from filesystem and classpath");
  }
}
