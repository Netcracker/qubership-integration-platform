package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;

/**
 * Deterministic JSON bytes and SHA-256 for a semantic revision. Unordered collections are sorted
 * by stable ids. Presentation order stays in explicit {@code order} fields.
 */
@ApplicationScoped
public class ChainSemanticCanonicalizer {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .defaultPropertyInclusion(
              JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
          .build();

  public byte[] canonicalBytes(ChainSemanticRevision revision) {
    Objects.requireNonNull(revision, "revision");
    try {
      return OBJECT_MAPPER.writeValueAsBytes(sorted(revision));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize chain semantic revision", e);
    }
  }

  public String sha256(ChainSemanticRevision revision) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(revision));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static ChainSemanticRevision sorted(ChainSemanticRevision revision) {
    return new ChainSemanticRevision(
        revision.schemaVersion(),
        revision.revisionId(),
        revision.chainIdentity(),
        revision.compilerContractVersion(),
        sortEntryPoints(revision.entryPoints()),
        sortNodes(revision.nodes()),
        sortRegions(revision.regions()),
        sortEdges(revision.executionEdges()),
        sortContainment(revision.containment()),
        sortMappings(revision.mappingIntents()),
        sortStrings(revision.constraints()),
        sortStrings(revision.assumptions()),
        sortCitations(revision.citations()));
  }

  private static List<SemanticEntryPoint> sortEntryPoints(List<SemanticEntryPoint> entryPoints) {
    List<SemanticEntryPoint> copy = new ArrayList<>(entryPoints.size());
    for (SemanticEntryPoint entryPoint : entryPoints) {
      copy.add(
          new SemanticEntryPoint(
              entryPoint.entryPointId(),
              entryPoint.triggerNodeId(),
              entryPoint.initialTargetNodeId(),
              entryPoint.order(),
              sortedProvenance(entryPoint.provenance()),
              entryPoint.presentation()));
    }
    copy.sort(Comparator.comparing(SemanticEntryPoint::entryPointId));
    return List.copyOf(copy);
  }

  private static List<SemanticNode> sortNodes(List<SemanticNode> nodes) {
    List<SemanticNode> copy = new ArrayList<>(nodes.size());
    for (SemanticNode node : nodes) {
      copy.add(withSortedProvenance(node));
    }
    copy.sort(Comparator.comparing(SemanticNode::nodeId));
    return List.copyOf(copy);
  }

  private static SemanticNode withSortedProvenance(SemanticNode node) {
    SemanticProvenance provenance = sortedProvenance(node.provenance());
    return switch (node) {
      case SemanticNode.Trigger trigger ->
          new SemanticNode.Trigger(trigger.nodeId(), trigger.capabilityKey(), provenance);
      case SemanticNode.ServiceCall call ->
          new SemanticNode.ServiceCall(
              call.nodeId(), call.serviceCallId(), call.operation(), provenance);
      case SemanticNode.Operation operation ->
          new SemanticNode.Operation(operation.nodeId(), operation.elementType(), provenance);
    };
  }

  private static List<SemanticRegion> sortRegions(List<SemanticRegion> regions) {
    List<SemanticRegion> copy = new ArrayList<>(regions);
    copy.sort(Comparator.comparing(SemanticRegion::regionId));
    return List.copyOf(copy);
  }

  private static List<SemanticExecutionEdge> sortEdges(List<SemanticExecutionEdge> edges) {
    List<SemanticExecutionEdge> copy = new ArrayList<>(edges);
    copy.sort(Comparator.comparing(SemanticExecutionEdge::edgeId));
    return List.copyOf(copy);
  }

  private static List<SemanticContainment> sortContainment(List<SemanticContainment> containment) {
    List<SemanticContainment> copy = new ArrayList<>(containment);
    copy.sort(
        Comparator.comparing(SemanticContainment::parentNodeId)
            .thenComparing(SemanticContainment::childNodeId)
            .thenComparing(SemanticContainment::role));
    return List.copyOf(copy);
  }

  private static List<MappingIntent> sortMappings(List<MappingIntent> mappingIntents) {
    List<MappingIntent> copy = new ArrayList<>(mappingIntents);
    copy.sort(Comparator.comparing(MappingIntent::mappingIntentId));
    return List.copyOf(copy);
  }

  private static List<String> sortStrings(List<String> values) {
    List<String> copy = new ArrayList<>(values);
    copy.sort(Comparator.naturalOrder());
    return List.copyOf(copy);
  }

  private static List<QipKnowledgeCitation> sortCitations(List<QipKnowledgeCitation> citations) {
    List<QipKnowledgeCitation> copy = new ArrayList<>(citations);
    copy.sort(
        Comparator.comparing(
            QipKnowledgeCitation::refId, Comparator.nullsFirst(String::compareTo)));
    return List.copyOf(copy);
  }

  private static SemanticProvenance sortedProvenance(SemanticProvenance provenance) {
    List<String> ids = new ArrayList<>(provenance.sourceFactIds());
    ids.sort(Comparator.naturalOrder());
    return new SemanticProvenance(ids);
  }
}
