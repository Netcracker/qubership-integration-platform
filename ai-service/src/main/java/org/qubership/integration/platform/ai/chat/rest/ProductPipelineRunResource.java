package org.qubership.integration.platform.ai.chat.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceSnapshot;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.create.CreateProductPipelineCoordinator;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.ProductPipelineRunView;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;

/**
 * Feature-gated local evidence endpoint for product CREATE runs. Enabled only when {@code
 * qip.evidence.snapshot.enabled=true}. Reads durable CAS run documents; never adapter memory.
 */
@Path("/api/v1/chat/conversations/{conversationId}/product-pipeline")
@ApplicationScoped
public class ProductPipelineRunResource {

  private static final Set<Kind> SAFE_DECODE_KINDS =
      EnumSet.of(
          Kind.REQUIREMENT_DRAFT,
          Kind.REQUIREMENT_BRIEF,
          Kind.IDS_BYPASS,
          Kind.IMPLEMENTATION_PLAN,
          Kind.PLAN_VALIDATION_RESULT,
          Kind.APPROVAL_RECORD,
          Kind.RUN_MANIFEST,
          Kind.CHAIN_PLAN_GRAPH,
          Kind.GRAPH_PATCH_ARTIFACT,
          Kind.GRAPH_ASSEMBLY_RESULT,
          Kind.COMPILER_VALIDATION_BUNDLE,
          Kind.MATERIALIZATION_CHECKPOINT,
          Kind.MATERIALIZATION_RESULT,
          Kind.CATALOG_CHAIN_SNAPSHOT,
          Kind.RECONCILE_RESULT,
          Kind.IDS_DOCUMENT,
          Kind.CATALOG_BINDING_HINT,
          Kind.DESIGN_PLAN_REPORT,
          Kind.DESIGN_EXECUTION_PLAN,
          Kind.CATALOG_BINDING_RESOLUTIONS,
          Kind.EXECUTION_TRACE,
          Kind.API_OPERATION_BINDINGS,
          Kind.ORDERED_GRAPH_PATCHES,
          Kind.EXECUTOR_VALIDATION_BUNDLE,
          Kind.VALIDATED_EXECUTION_BUNDLE,
          Kind.MATERIALIZATION_REQUEST,
          Kind.DESIGN_EXECUTION_CHECKPOINT,
          Kind.DESIGN_EXECUTION_RESULT);

  private final boolean evidenceEnabled;
  private final CreateRunSelectionService selectionService;
  private final CreateProductPipelineCoordinator coordinator;
  private final ProductPipelineArtifactStore artifactStore;
  private final ConversationEvidenceStore evidenceStore;
  private final ObjectMapper objectMapper;

  @Inject
  public ProductPipelineRunResource(
      @ConfigProperty(name = "qip.evidence.snapshot.enabled", defaultValue = "false")
          boolean evidenceEnabled,
      CreateRunSelectionService selectionService,
      CreateProductPipelineCoordinator coordinator,
      ProductPipelineArtifactStore artifactStore,
      ConversationEvidenceStore evidenceStore,
      ObjectMapper objectMapper) {
    this.evidenceEnabled = evidenceEnabled;
    this.selectionService = selectionService;
    this.coordinator = coordinator;
    this.artifactStore = artifactStore;
    this.evidenceStore = evidenceStore;
    this.objectMapper = objectMapper;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public ProductPipelineRunView get(@PathParam("conversationId") String conversationId) {
    if (!evidenceEnabled) {
      throw new NotFoundException("product-pipeline evidence view is disabled");
    }
    var selection =
        selectionService
            .existing(conversationId)
            .orElseThrow(() -> new NotFoundException("no product-pipeline selection"));

    ProductPipelineRunDocument document =
        coordinator
            .loadRun(conversationId)
            .orElseThrow(() -> new NotFoundException("no durable product-pipeline run"));

    RunManifest manifest = selection.runManifest();

    EvidenceSnapshot.Knowledge knowledgeContext =
        evidenceStore
            .find(conversationId)
            .map(accumulator -> accumulator.toSnapshot(conversationId).knowledge())
            .filter(knowledge -> knowledge.packageRef() != null)
            .orElseGet(
                () ->
                    new EvidenceSnapshot.Knowledge(
                        manifest == null ? null : manifest.knowledgePackage(),
                        List.of(),
                        0));

    List<Reference> committed = new ArrayList<>(collectCommittedReferences(document));
    // Include latest durable artifacts that may not yet be mirrored into stage outputRefs
    // (for example APPROVAL_RECORD after terminal approval).
    for (Kind kind : SAFE_DECODE_KINDS) {
      artifactStore
          .latest(document.run().runId(), kind)
          .map(Revision::reference)
          .ifPresent(committed::add);
    }
    addOrderedPatchReferencesFromAssembly(document.run().runId(), committed);
    LinkedHashSet<Reference> unique = new LinkedHashSet<>(committed);
    committed = List.copyOf(unique);
    List<String> kinds = new ArrayList<>();
    Set<String> seenKinds = new LinkedHashSet<>();
    Map<String, Object> decoded = new LinkedHashMap<>();
    String compilationId = document.run().runId();
    for (Reference reference : committed) {
      String kindName = reference.kind().name();
      if (seenKinds.add(kindName)) {
        kinds.add(kindName);
      }
      if (!SAFE_DECODE_KINDS.contains(reference.kind())) {
        continue;
      }
      artifactStore
          .get(compilationId, reference)
          .ifPresent(
              revision ->
                  decoded.putIfAbsent(
                      kindName + "#" + revision.artifactId(), decodeSafe(revision)));
    }

    String compilerPackageDigest = null;
    String pipelineIndexDigest = null;
    String resolvedDagDigest = null;
    if (manifest != null && manifest.compilerRunPin() != null) {
      CompilerRunPin pin = manifest.compilerRunPin();
      compilerPackageDigest = pin.compilerPackageDigest();
      pipelineIndexDigest = pin.pipelineIndexDigest();
      ResolvedCompilerDag dag = pin.resolvedDag();
      if (dag != null) {
        resolvedDagDigest = dag.digest();
      }
    }

    String approvedPlanContentHash = extractApprovedPlanHash(decoded);
    String materializedChainId = extractMaterializedChainId(decoded);
    Boolean reconcileMatches = extractReconcileMatches(decoded);

    return new ProductPipelineRunView(
        conversationId,
        document.run().status().name(),
        document.run().runRevision(),
        manifest,
        document.attempts(),
        document.transitions(),
        kinds,
        decoded,
        knowledgeContext,
        compilerPackageDigest,
        pipelineIndexDigest,
        resolvedDagDigest,
        approvedPlanContentHash,
        materializedChainId,
        reconcileMatches);
  }

  private static List<Reference> collectCommittedReferences(ProductPipelineRunDocument document) {
    LinkedHashSet<Reference> refs = new LinkedHashSet<>();
    if (document.run().runManifestRef() != null) {
      refs.add(document.run().runManifestRef());
    }
    for (StageSnapshot stage : document.run().stages()) {
      refs.addAll(stage.outputRefs());
      refs.addAll(stage.candidateReferences());
      if (stage.approvableReference() != null) {
        refs.add(stage.approvableReference());
      }
    }
    for (StageAttempt attempt : document.attempts()) {
      refs.addAll(attempt.outputs());
    }
    return List.copyOf(refs);
  }

  private void addOrderedPatchReferencesFromAssembly(String runId, List<Reference> committed) {
    Optional<Revision> assembly =
        committed.stream()
            .filter(ref -> ref.kind() == Kind.GRAPH_ASSEMBLY_RESULT)
            .map(ref -> artifactStore.get(runId, ref))
            .flatMap(Optional::stream)
            .findFirst()
            .or(
                () ->
                    artifactStore.latest(runId, Kind.GRAPH_ASSEMBLY_RESULT));
    if (assembly.isEmpty()) {
      return;
    }
    try {
      org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult result =
          artifactStore.payload(
              assembly.orElseThrow(),
              org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult
                  .class);
      if (result.orderedPatchReferences() == null) {
        return;
      }
      committed.addAll(result.orderedPatchReferences());
    } catch (RuntimeException ignored) {
      // Leave evidence without patch refs when assembly payload cannot be decoded.
    }
  }

  private Object decodeSafe(Revision revision) {
    JsonNode payload = revision.payload();
    if (payload == null || payload.isNull()) {
      return Map.of();
    }
    Kind kind = revision.kind();
    if (kind == Kind.GRAPH_PATCH_ARTIFACT) {
      return summarizeGraphPatch(payload);
    }
    if (kind == Kind.CATALOG_CHAIN_SNAPSHOT) {
      return redactCatalogSnapshot(objectMapper, payload);
    }
    if (kind == Kind.CHAIN_PLAN_GRAPH || kind == Kind.GRAPH_ASSEMBLY_RESULT) {
      return redactChainPlanGraphEvidence(objectMapper, payload);
    }
    return objectMapper.convertValue(payload, Object.class);
  }

  static Map<String, Object> summarizeGraphPatch(JsonNode payload) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("ownerCapabilityId", textOrEmpty(payload.get("ownerCapabilityId")));
    summary.put("applicability", textOrEmpty(payload.get("applicability")));
    summary.put("baseGraphDigest", textOrEmpty(payload.get("baseGraphDigest")));
    summary.put("resultGraphDigest", textOrEmpty(payload.get("resultGraphDigest")));
    summary.put("invocationKey", textOrEmpty(payload.get("invocationKey")));
    JsonNode patch = payload.get("patch");
    int nodeOps = 0;
    int edgeOps = 0;
    int propertyOps = 0;
    int chainOps = 0;
    if (patch != null && patch.isObject()) {
      nodeOps = arraySize(patch.get("nodePatches"));
      edgeOps = arraySize(patch.get("edgePatches"));
      propertyOps = arraySize(patch.get("propertyPatches"));
      chainOps = arraySize(patch.get("chainPatches"));
    }
    summary.put("nodeOperationCount", nodeOps);
    summary.put("edgeOperationCount", edgeOps);
    summary.put("propertyOperationCount", propertyOps);
    summary.put("chainOperationCount", chainOps);
    summary.put("operationCount", nodeOps + edgeOps + propertyOps + chainOps);
    return Map.copyOf(summary);
  }

  /**
   * Fail-closed evidence redaction for plan graphs and assembly results: keep topology, clear every
   * {@code node.properties} bag (no sensitive-key allowlist).
   */
  static Object redactChainPlanGraphEvidence(ObjectMapper objectMapper, JsonNode payload) {
    Object converted = objectMapper.convertValue(payload, Object.class);
    if (!(converted instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    return Map.copyOf(clearNodePropertiesDeep(raw));
  }

  private static Map<String, Object> clearNodePropertiesDeep(Map<?, ?> raw) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key) || entry.getValue() == null) {
        continue;
      }
      Object value = entry.getValue();
      if ("nodes".equals(key) && value instanceof List<?> nodes) {
        copy.put(key, clearNodeListProperties(nodes));
      } else if ("graph".equals(key) && value instanceof Map<?, ?> graph) {
        copy.put(key, Map.copyOf(clearNodePropertiesDeep(graph)));
      } else {
        copy.put(key, value);
      }
    }
    return copy;
  }

  private static List<Object> clearNodeListProperties(List<?> nodes) {
    List<Object> redacted = new ArrayList<>();
    for (Object node : nodes) {
      if (node instanceof Map<?, ?> nodeMap) {
        Map<String, Object> nodeCopy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : nodeMap.entrySet()) {
          if (!(field.getKey() instanceof String fieldKey)) {
            continue;
          }
          if ("properties".equals(fieldKey)) {
            nodeCopy.put(fieldKey, Map.of());
          } else if (field.getValue() != null) {
            nodeCopy.put(fieldKey, field.getValue());
          }
        }
        redacted.add(Map.copyOf(nodeCopy));
      } else {
        redacted.add(node);
      }
    }
    return List.copyOf(redacted);
  }

  static Object redactCatalogSnapshot(ObjectMapper objectMapper, JsonNode payload) {
    Object converted = objectMapper.convertValue(payload, Object.class);
    if (!(converted instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, Object> snapshot = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        continue;
      }
      if ("elements".equals(key) && entry.getValue() instanceof List<?> elements) {
        List<Object> redacted = new ArrayList<>();
        for (Object element : elements) {
          if (element instanceof Map<?, ?> elementMap) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : elementMap.entrySet()) {
              if (!(field.getKey() instanceof String fieldKey)) {
                continue;
              }
              if ("properties".equals(fieldKey) || "scriptProperties".equals(fieldKey)) {
                copy.put(fieldKey, Map.of());
              } else if (field.getValue() != null) {
                // Map.copyOf rejects null values; catalog DTO fields may be null.
                copy.put(fieldKey, field.getValue());
              }
            }
            redacted.add(Map.copyOf(copy));
          } else {
            redacted.add(element);
          }
        }
        snapshot.put(key, List.copyOf(redacted));
      } else if (entry.getValue() != null) {
        snapshot.put(key, entry.getValue());
      }
    }
    return Map.copyOf(snapshot);
  }

  private static String extractApprovedPlanHash(Map<String, Object> decoded) {
    String implementationPlanHash = null;
    String anyApprovalHash = null;
    for (Map.Entry<String, Object> entry : decoded.entrySet()) {
      if (!(entry.getKey().equals("APPROVAL_RECORD")
          || entry.getKey().startsWith("APPROVAL_RECORD#"))) {
        continue;
      }
      if (!(entry.getValue() instanceof Map<?, ?> map)) {
        continue;
      }
      Object hash = map.get("targetContentHash");
      if (!(hash instanceof String value) || value.isBlank()) {
        continue;
      }
      if (anyApprovalHash == null) {
        anyApprovalHash = value;
      }
      Object target = map.get("target");
      if (target instanceof Map<?, ?> targetMap) {
        Object kind = targetMap.get("kind");
        if ("IMPLEMENTATION_PLAN".equals(kind)) {
          implementationPlanHash = value;
        }
      }
    }
    return implementationPlanHash != null ? implementationPlanHash : anyApprovalHash;
  }

  private static String extractMaterializedChainId(Map<String, Object> decoded) {
    Object materialization = firstDecoded(decoded, "MATERIALIZATION_RESULT");
    if (materialization instanceof Map<?, ?> map) {
      Object chainId = map.get("chainId");
      if (chainId instanceof String value && !value.isBlank()) {
        return value;
      }
    }
    Object snapshot = firstDecoded(decoded, "CATALOG_CHAIN_SNAPSHOT");
    if (snapshot instanceof Map<?, ?> map) {
      Object chainId = map.get("chainId");
      if (chainId instanceof String value && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static Boolean extractReconcileMatches(Map<String, Object> decoded) {
    Object reconcile = firstDecoded(decoded, "RECONCILE_RESULT");
    if (!(reconcile instanceof Map<?, ?> map)) {
      return null;
    }
    Object matches = map.get("matches");
    return matches instanceof Boolean value ? value : null;
  }

  private static Object firstDecoded(Map<String, Object> decoded, String kindPrefix) {
    for (Map.Entry<String, Object> entry : decoded.entrySet()) {
      if (entry.getKey().equals(kindPrefix) || entry.getKey().startsWith(kindPrefix + "#")) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String textOrEmpty(JsonNode node) {
    if (node == null || node.isNull()) {
      return "";
    }
    String text = node.asText();
    return text == null ? "" : text;
  }

  private static int arraySize(JsonNode node) {
    return node != null && node.isArray() ? node.size() : 0;
  }
}
