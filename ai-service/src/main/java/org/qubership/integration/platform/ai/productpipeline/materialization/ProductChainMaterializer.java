package org.qubership.integration.platform.ai.productpipeline.materialization;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogElementAdoptionBinder;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializeResult;
import org.qubership.integration.platform.ai.integration.catalog.materialize.CatalogGraphMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;

/** Durable crash-safe materialization through read-back. */
@ApplicationScoped
public class ProductChainMaterializer {

  private static final int SCHEMA_VERSION = 1;
  private static final String STAGE_ID = "materialization";
  private static final String PRODUCER_VERSION = "1";

  private final CatalogMutationGateway catalog;
  private final ProductPipelineArtifactStore artifactStore;
  private final ChainCatalogFactsService factsService;
  private final ChainPlanGraphImporter graphImporter;

  @Inject
  public ProductChainMaterializer(
      CatalogMutationGateway catalog,
      ProductPipelineArtifactStore artifactStore,
      ChainCatalogFactsService factsService,
      ChainPlanGraphImporter graphImporter) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.factsService = Objects.requireNonNull(factsService, "factsService");
    this.graphImporter = Objects.requireNonNull(graphImporter, "graphImporter");
  }

  public MaterializationResult resume(Inputs inputs, MaterializationCheckpoint checkpoint) {
    Objects.requireNonNull(inputs, "inputs");
    MaterializationCheckpoint current =
        checkpoint == null ? initialCheckpoint(inputs.runId()) : checkpoint;
    String executionKey = normalize(current.executionKey());
    if (executionKey == null) {
      executionKey = inputs.runId();
    }

    String chainId = normalize(current.chainId());
    Map<String, String> map =
        new LinkedHashMap<>(
            current.materializationMap() == null
                ? Map.of()
                : current.materializationMap().nodeIdToElementId());
    MaterializationPhase phase = current.completedPhase();
    MaterializationMap owned = current.materializationMap();

    if (phase == null) {
      chainId = ensureChain(inputs, executionKey);
      current = appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.CHAIN, map, null);
      phase = current.completedPhase();
    }

    if (phase == MaterializationPhase.CHAIN
        || phase == MaterializationPhase.PROPERTIES
        || phase == MaterializationPhase.CONNECTIONS) {
      appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.CHAIN, map, null);
      owned = applyGraph(inputs, chainId, map);
      map = new LinkedHashMap<>(owned.nodeIdToElementId());
      current = appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.ELEMENTS, map, null);
      phase = current.completedPhase();
    }

    if (phase == MaterializationPhase.ELEMENTS) {
      factsService.load(chainId);
      current =
          appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.READ_BACK, map, null);
      phase = current.completedPhase();
    }

    return new MaterializationResult(
        SCHEMA_VERSION,
        chainId,
        copyOwned(chainId, owned, map),
        inputs.approvedGraphDigest(),
        phase);
  }

  public MaterializationResult markReconciled(Inputs inputs, MaterializationResult current) {
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(current, "current");
    MaterializationMap owned = current.materializationMap();
    Map<String, String> map = owned == null ? Map.of() : owned.nodeIdToElementId();
    appendCheckpoint(
        inputs,
        inputs.runId(),
        current.chainId(),
        MaterializationPhase.RECONCILE,
        map,
        null);
    return new MaterializationResult(
        SCHEMA_VERSION,
        current.chainId(),
        copyOwned(current.chainId(), owned, map),
        current.approvedGraphDigest(),
        MaterializationPhase.RECONCILE);
  }

  public MaterializationResult markComplete(Inputs inputs, MaterializationResult current) {
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(current, "current");
    MaterializationMap owned = current.materializationMap();
    Map<String, String> map = owned == null ? Map.of() : owned.nodeIdToElementId();
    appendCheckpoint(
        inputs, inputs.runId(), current.chainId(), MaterializationPhase.COMPLETE, map, null);
    return new MaterializationResult(
        SCHEMA_VERSION,
        current.chainId(),
        copyOwned(current.chainId(), owned, map),
        current.approvedGraphDigest(),
        MaterializationPhase.COMPLETE);
  }

  public ChainCatalogFacts readBack(String chainId) {
    return factsService.load(chainId);
  }

  private String ensureChain(Inputs inputs, String executionKey) {
    return catalog
        .resolveOrCreateChain(
            executionKey, inputs.graph().chain().name(), inputs.graph().chain().description())
        .await()
        .indefinitely();
  }

  private MaterializationMap applyGraph(
      Inputs inputs, String chainId, Map<String, String> existingMap) {
    Map<String, String> seededMap =
        seedMaterializationMapFromReadBack(inputs.graph(), chainId, existingMap);
    MaterializationMap checkpointMap =
        new MaterializationMap(chainId, Map.copyOf(seededMap), Map.of(), Map.of());
    ChainPlanGraph desired = inputs.graph();
    ChainPlanGraph current = CatalogGraphMaterializer.emptyCurrent(desired);
    CatalogGraphMaterializeResult result =
        catalog.applyGraph(current, desired, checkpointMap).await().indefinitely();
    if (!result.succeeded()) {
      throw new IllegalStateException(
          "graph materialization failed: "
              + (result.error() == null ? result.failedNodeIds() : result.error()));
    }
    MaterializationMap owned = result.materializationMap();
    return new MaterializationMap(
        chainId,
        owned.nodeIdToElementId(),
        owned.semanticEdgeOwnerElementIds(),
        owned.mappingIntentExecutionNodeIds());
  }

  private static MaterializationMap copyOwned(
      String chainId, MaterializationMap owned, Map<String, String> nodeIds) {
    if (owned == null) {
      return new MaterializationMap(chainId, Map.copyOf(nodeIds), Map.of(), Map.of());
    }
    return new MaterializationMap(
        chainId,
        owned.nodeIdToElementId(),
        owned.semanticEdgeOwnerElementIds(),
        owned.mappingIntentExecutionNodeIds());
  }

  private Map<String, String> seedMaterializationMapFromReadBack(
      ChainPlanGraph desired, String chainId, Map<String, String> checkpointMap) {
    ChainCatalogFacts facts = factsService.load(chainId);
    Objects.requireNonNull(graphImporter.importChain(facts), "imported");
    return CatalogElementAdoptionBinder.mergeImportedBindings(
        desired, checkpointMap, facts.elements());
  }

  private MaterializationCheckpoint appendCheckpoint(
      Inputs inputs,
      String executionKey,
      String chainId,
      MaterializationPhase completedPhase,
      Map<String, String> map,
      String pendingNodeId) {
    Map<String, String> externalKeys =
        executionKey == null ? Map.of() : Map.of("chainPublicationExecutionKey", executionKey);
    MaterializationCheckpoint payload =
        new MaterializationCheckpoint(
            SCHEMA_VERSION,
            executionKey,
            chainId,
            completedPhase,
            new MaterializationMap(chainId, Map.copyOf(map), Map.of(), Map.of()),
            pendingNodeId,
            externalKeys);
    artifactStore.append(
        new AppendCommand(
            inputs.runId(),
            Kind.MATERIALIZATION_CHECKPOINT,
            String.valueOf(SCHEMA_VERSION),
            MaterializationCapability.CAPABILITY_ID,
            PRODUCER_VERSION,
            payload,
            List.of(),
            null,
            provenance(inputs.runId(), inputs.runManifest())));
    return payload;
  }

  private static MaterializationCheckpoint initialCheckpoint(String executionKey) {
    return new MaterializationCheckpoint(
        SCHEMA_VERSION,
        executionKey,
        null,
        null,
        new MaterializationMap(null, Map.of(), Map.of(), Map.of()),
        null,
        Map.of());
  }

  private static ArtifactProvenance provenance(String runId, RunManifest manifest) {
    return new ArtifactProvenance(
        runId,
        STAGE_ID,
        manifest == null || manifest.profileId() == null ? "unknown" : manifest.profileId(),
        manifest == null || manifest.profileVersion() == null ? "1" : manifest.profileVersion(),
        manifest == null || manifest.profileDigest() == null ? "unknown" : manifest.profileDigest(),
        MaterializationCapability.CAPABILITY_ID,
        PRODUCER_VERSION,
        manifest == null || manifest.dependencyClosureDigest() == null
            ? "unknown"
            : manifest.dependencyClosureDigest());
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public record Inputs(
      String runId, ChainPlanGraph graph, RunManifest runManifest, String approvedGraphDigest) {

    public Inputs {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(graph, "graph");
      approvedGraphDigest = approvedGraphDigest == null ? "" : approvedGraphDigest;
    }
  }
}
