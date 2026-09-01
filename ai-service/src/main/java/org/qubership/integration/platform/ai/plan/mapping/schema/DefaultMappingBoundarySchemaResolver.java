package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

public class DefaultMappingBoundarySchemaResolver implements MappingBoundarySchemaResolver {

  private static final String SCHEMA_VERSION = "1";

  private final CompilationArtifacts artifacts;
  private final String compilationId;
  private final ObjectMapper objectMapper;

  public DefaultMappingBoundarySchemaResolver(
      CompilationArtifacts artifacts, String compilationId, ObjectMapper objectMapper) {
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    if (compilationId == null || compilationId.isBlank()) {
      throw new IllegalArgumentException("compilationId is required");
    }
    this.compilationId = compilationId.trim();
    this.objectMapper =
        Objects.requireNonNull(objectMapper, "objectMapper")
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  @Override
  public MappingBoundarySchemas resolve(
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      MappingIntent intent,
      Map<String, MappingEnvelope> envelopesByTransformNodeId) {
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(intent, "intent");
    List<ResolvedServiceCallBinding> bindingList = bindings == null ? List.of() : bindings;
    Map<String, MappingEnvelope> envelopes =
        envelopesByTransformNodeId == null ? Map.of() : envelopesByTransformNodeId;
    List<MappingSchemaSide> persisted = persistedSides();
    MappingSchemaSide source =
        resolveSide(
            revision,
            bindingList,
            persisted,
            envelopes,
            intent.sourceRef(),
            intent.sourcePort());
    MappingSchemaSide target =
        resolveSide(
            revision,
            bindingList,
            persisted,
            envelopes,
            intent.targetRef(),
            intent.targetPort());
    return new MappingBoundarySchemas(source, target);
  }

  private MappingSchemaSide resolveSide(
      ChainSemanticRevision revision,
      List<ResolvedServiceCallBinding> bindings,
      List<MappingSchemaSide> persisted,
      Map<String, MappingEnvelope> envelopes,
      String ref,
      MappingPort port) {
    SemanticNode node = findNode(revision, ref, port);
    if (node instanceof SemanticNode.Operation && port == MappingPort.OUTPUT) {
      return sideFromEnvelope(node, envelopes.get(node.nodeId()));
    }
    if (node instanceof SemanticNode.Trigger trigger && port != MappingPort.REQUEST) {
      return resolveTriggerOutput(trigger, bindings, persisted, port);
    }
    return selectUnique(persisted, ownerId(node, bindings), port, port);
  }

  private MappingSchemaSide resolveTriggerOutput(
      SemanticNode.Trigger trigger,
      List<ResolvedServiceCallBinding> bindings,
      List<MappingSchemaSide> persisted,
      MappingPort resultDirection) {
    for (ResolvedServiceCallBinding binding : bindings) {
      if (trigger.nodeId().equals(binding.targetNodeId())) {
        return selectUnique(
            persisted, binding.serviceCallId(), MappingPort.REQUEST, resultDirection);
      }
    }
    return selectUnique(persisted, trigger.nodeId(), MappingPort.OUTPUT, resultDirection);
  }

  private MappingSchemaSide sideFromEnvelope(SemanticNode node, MappingEnvelope envelope) {
    if (envelope == null || envelope.target() == null) {
      throw new IllegalStateException("No prior envelope for transform node " + node.nodeId());
    }
    byte[] bytes = write(envelope.target());
    return new MappingSchemaSide(
        SCHEMA_VERSION,
        node.nodeId(),
        null,
        MappingPort.OUTPUT,
        null,
        null,
        sha256(bytes),
        "envelope:" + envelope.digest() + ":target",
        readTree(bytes));
  }

  private List<MappingSchemaSide> persistedSides() {
    List<MappingSchemaSide> sides = new ArrayList<>();
    for (CompilationArtifacts.Revision revision :
        artifacts.history(compilationId, Kind.MAPPING_SCHEMA_SIDE)) {
      sides.add(artifacts.payload(revision, MappingSchemaSide.class));
    }
    return sides;
  }

  private static MappingSchemaSide selectUnique(
      List<MappingSchemaSide> all,
      String ownerId,
      MappingPort persistDirection,
      MappingPort resultDirection) {
    List<MappingSchemaSide> matching = new ArrayList<>();
    for (MappingSchemaSide side : all) {
      if (ownerId.equals(side.serviceCallId())
          && side.direction() == persistDirection
          && !"parameters".equals(side.contentType())) {
        matching.add(side);
      }
    }
    if (matching.isEmpty()) {
      return new MappingSchemaSide(
          SCHEMA_VERSION, ownerId, null, resultDirection, null, null, null, null, null);
    }
    if (persistDirection == MappingPort.RESPONSE) {
      LinkedHashSet<String> statuses = new LinkedHashSet<>();
      for (MappingSchemaSide side : matching) {
        statuses.add(side.responseCode());
      }
      if (statuses.size() != 1) {
        throw new IllegalStateException(
            "Ambiguous response status for " + ownerId + ": " + String.join(", ", statuses));
      }
    }
    LinkedHashSet<String> types = new LinkedHashSet<>();
    for (MappingSchemaSide side : matching) {
      types.add(side.contentType());
    }
    if (types.size() != 1) {
      throw new IllegalStateException(
          "Ambiguous content types for "
              + ownerId
              + " "
              + resultDirection
              + ": "
              + String.join(", ", types));
    }
    MappingSchemaSide chosen = matching.getFirst();
    if (chosen.direction() == resultDirection) {
      return chosen;
    }
    return new MappingSchemaSide(
        chosen.schemaVersion(),
        chosen.serviceCallId(),
        chosen.operationId(),
        resultDirection,
        chosen.contentType(),
        chosen.responseCode(),
        chosen.sha256(),
        chosen.provenance(),
        chosen.schema());
  }

  private static SemanticNode findNode(
      ChainSemanticRevision revision, String ref, MappingPort port) {
    if (ref == null || ref.isBlank()) {
      throw new IllegalStateException("Mapping ref is required");
    }
    for (SemanticNode node : revision.nodes()) {
      if (ref.equals(node.nodeId())) {
        return node;
      }
    }
    for (SemanticNode node : revision.nodes()) {
      if (node instanceof SemanticNode.ServiceCall call && ref.equals(call.serviceCallId())) {
        return call;
      }
    }
    SemanticExecutionEdge edge = findEdge(revision, ref);
    if (edge != null) {
      return endpointForPort(revision, edge, port);
    }
    throw new IllegalStateException("Unknown mapping ref " + ref);
  }

  private static SemanticExecutionEdge findEdge(ChainSemanticRevision revision, String edgeId) {
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      if (edgeId.equals(edge.edgeId())) {
        return edge;
      }
    }
    return null;
  }

  /**
   * After design-input, mapping intents sit on an edge: both refs are that edge id. OUTPUT/RESPONSE
   * is the producer side; REQUEST is the consumer. A transform node on the edge is skipped so the
   * schema comes from the trigger or service call.
   */
  private static SemanticNode endpointForPort(
      ChainSemanticRevision revision, SemanticExecutionEdge edge, MappingPort port) {
    boolean producer = port != MappingPort.REQUEST;
    String nodeId = producer ? edge.sourceNodeId() : edge.targetNodeId();
    SemanticNode node = requireNode(revision, nodeId);
    if (!isTransform(node)) {
      return node;
    }
    String walked =
        producer
            ? uniqueNeighbor(revision, node.nodeId(), true)
            : uniqueNeighbor(revision, node.nodeId(), false);
    return requireNode(revision, walked);
  }

  private static String uniqueNeighbor(
      ChainSemanticRevision revision, String transformNodeId, boolean incoming) {
    String found = null;
    for (SemanticExecutionEdge edge : revision.executionEdges()) {
      boolean match =
          incoming
              ? transformNodeId.equals(edge.targetNodeId())
              : transformNodeId.equals(edge.sourceNodeId());
      if (!match) {
        continue;
      }
      String neighbor = incoming ? edge.sourceNodeId() : edge.targetNodeId();
      if (found != null && !found.equals(neighbor)) {
        throw new IllegalStateException(
            "Ambiguous mapping endpoint around transform node " + transformNodeId);
      }
      found = neighbor;
    }
    if (found == null) {
      throw new IllegalStateException(
          "No mapping endpoint adjacent to transform node " + transformNodeId);
    }
    return found;
  }

  private static SemanticNode requireNode(ChainSemanticRevision revision, String nodeId) {
    for (SemanticNode node : revision.nodes()) {
      if (nodeId.equals(node.nodeId())) {
        return node;
      }
    }
    throw new IllegalStateException("Unknown mapping ref " + nodeId);
  }

  private static boolean isTransform(SemanticNode node) {
    if (!(node instanceof SemanticNode.Operation operation)) {
      return false;
    }
    String type = operation.elementType();
    return MappingExecutionSite.ELEMENT_TYPE.equals(type)
        || MappingExecutionSite.SCRIPT_ELEMENT_TYPE.equals(type);
  }

  private static String ownerId(
      SemanticNode node, List<ResolvedServiceCallBinding> bindings) {
    if (node instanceof SemanticNode.ServiceCall call) {
      return call.serviceCallId();
    }
    if (node instanceof SemanticNode.Trigger trigger) {
      for (ResolvedServiceCallBinding binding : bindings) {
        if (trigger.nodeId().equals(binding.targetNodeId())) {
          return binding.serviceCallId();
        }
      }
      return trigger.nodeId();
    }
    return node.nodeId();
  }

  private byte[] write(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize envelope target", e);
    }
  }

  private JsonNode readTree(byte[] bytes) {
    try {
      return objectMapper.readTree(bytes);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot parse envelope target as JSON", e);
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
