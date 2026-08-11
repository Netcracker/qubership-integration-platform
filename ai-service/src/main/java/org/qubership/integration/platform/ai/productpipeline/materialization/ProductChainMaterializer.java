package org.qubership.integration.platform.ai.productpipeline.materialization;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanPropertiesMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanSkeletonMaterializer;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
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
  private final PendingNodeRecoveryResolver resolver;
  private final ProductPipelineArtifactStore artifactStore;
  private final ChainCatalogFactsService factsService;

  @Inject
  public ProductChainMaterializer(
      CatalogMutationGateway catalog,
      PendingNodeRecoveryResolver resolver,
      ProductPipelineArtifactStore artifactStore,
      ChainCatalogFactsService factsService) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.factsService = Objects.requireNonNull(factsService, "factsService");
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

    if (phase == null) {
      chainId = ensureChain(inputs, executionKey);
      current = appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.CHAIN, map, null);
      phase = current.completedPhase();
    }

    if (phase == MaterializationPhase.CHAIN) {
      map = materializeElements(inputs, current, chainId, map);
      current = appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.ELEMENTS, map, null);
      phase = current.completedPhase();
    }

    if (phase == MaterializationPhase.ELEMENTS) {
      applyProperties(inputs.graph(), chainId, map);
      current =
          appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.PROPERTIES, map, null);
      phase = current.completedPhase();
    }

    if (phase == MaterializationPhase.PROPERTIES) {
      applyConnections(inputs.graph(), chainId, map);
      current =
          appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.CONNECTIONS, map, null);
      phase = current.completedPhase();
    }

    if (phase == MaterializationPhase.CONNECTIONS) {
      factsService.load(chainId);
      current =
          appendCheckpoint(inputs, executionKey, chainId, MaterializationPhase.READ_BACK, map, null);
      phase = current.completedPhase();
    }

    if (phase == MaterializationPhase.READ_BACK
        || phase == MaterializationPhase.RECONCILE
        || phase == MaterializationPhase.COMPLETE) {
      return new MaterializationResult(
          SCHEMA_VERSION,
          chainId,
          new MaterializationMap(chainId, Map.copyOf(map)),
          inputs.approvedGraphDigest(),
          phase);
    }

    return new MaterializationResult(
        SCHEMA_VERSION,
        chainId,
        new MaterializationMap(chainId, Map.copyOf(map)),
        inputs.approvedGraphDigest(),
        phase);
  }

  public MaterializationResult markReconciled(Inputs inputs, MaterializationResult current) {
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(current, "current");
    Map<String, String> map =
        current.materializationMap() == null
            ? Map.of()
            : current.materializationMap().nodeIdToElementId();
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
        new MaterializationMap(current.chainId(), Map.copyOf(map)),
        current.approvedGraphDigest(),
        MaterializationPhase.RECONCILE);
  }

  public MaterializationResult markComplete(Inputs inputs, MaterializationResult current) {
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(current, "current");
    Map<String, String> map =
        current.materializationMap() == null
            ? Map.of()
            : current.materializationMap().nodeIdToElementId();
    appendCheckpoint(
        inputs, inputs.runId(), current.chainId(), MaterializationPhase.COMPLETE, map, null);
    return new MaterializationResult(
        SCHEMA_VERSION,
        current.chainId(),
        new MaterializationMap(current.chainId(), Map.copyOf(map)),
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

  private Map<String, String> materializeElements(
      Inputs inputs, MaterializationCheckpoint checkpoint, String chainId, Map<String, String> existingMap) {
    Map<String, String> map = new LinkedHashMap<>(existingMap);
    String resumePending = normalize(checkpoint.pendingNodeId());
    for (ChainPlanNode node : ChainPlanSkeletonMaterializer.orderParentBeforeChild(inputs.graph())) {
      if (map.containsKey(node.nodeId())) {
        continue;
      }
      if (resumePending != null && resumePending.equals(node.nodeId())) {
        List<CatalogElementResponseDto> catalogFacts =
            catalog.listElements(chainId).await().indefinitely();
        String recovered =
            resolver.resolve(node, catalogFacts, new MaterializationMap(chainId, Map.copyOf(map)));
        if (recovered != null) {
          map.put(node.nodeId(), recovered);
          resumePending = null;
          continue;
        }
      }
      appendCheckpoint(inputs, inputs.runId(), chainId, MaterializationPhase.CHAIN, map, node.nodeId());
      String createdElementId =
          catalog
              .materializeSkeletonElement(
                  inputs.graph(), node, chainId, new MaterializationMap(chainId, Map.copyOf(map)))
              .await()
              .indefinitely();
      map.put(node.nodeId(), createdElementId);
      resumePending = null;
    }
    return map;
  }

  private void applyProperties(ChainPlanGraph graph, String chainId, Map<String, String> map) {
    ChainPlanPropertiesMaterializer.PropertiesApplyResult result =
        catalog
            .applyProperties(graph, new MaterializationMap(chainId, Map.copyOf(map)))
            .await()
            .indefinitely();
    if (result.failedNodeIds() != null && !result.failedNodeIds().isEmpty()) {
      throw new IllegalStateException(
          "property materialization failed for nodes " + result.failedNodeIds());
    }
  }

  private void applyConnections(ChainPlanGraph graph, String chainId, Map<String, String> map) {
    ChainPlanConnectionsMaterializer.ConnectionsApplyResult result =
        catalog
            .applyConnections(graph, new MaterializationMap(chainId, Map.copyOf(map)))
            .await()
            .indefinitely();
    if (result.failedEdgeIds() != null && !result.failedEdgeIds().isEmpty()) {
      throw new IllegalStateException(
          "connection materialization failed for edges " + result.failedEdgeIds());
    }
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
            new MaterializationMap(chainId, Map.copyOf(map)),
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
        new MaterializationMap(null, Map.of()),
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
