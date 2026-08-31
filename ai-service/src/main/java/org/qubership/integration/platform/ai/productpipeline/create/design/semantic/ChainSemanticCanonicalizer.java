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
          new SemanticNode.Trigger(
              trigger.nodeId(), trigger.interactionId(), trigger.capabilityKey(), provenance);
      case SemanticNode.ServiceCall call ->
          new SemanticNode.ServiceCall(
              call.nodeId(), call.serviceCallId(), call.operation(), provenance);
      case SemanticNode.Operation operation ->
          new SemanticNode.Operation(operation.nodeId(), operation.elementType(), provenance);
    };
  }

  private static List<SemanticRegion> sortRegions(List<SemanticRegion> regions) {
    List<SemanticRegion> copy = new ArrayList<>(regions.size());
    for (SemanticRegion region : regions) {
      copy.add(canonicalRegion(region));
    }
    copy.sort(Comparator.comparing(SemanticRegion::regionId));
    return List.copyOf(copy);
  }

  private static SemanticRegion canonicalRegion(SemanticRegion region) {
    return switch (region) {
      case SemanticRegion.Sequence sequence ->
          new SemanticRegion.Sequence(sequence.regionId(), sequence.memberNodeIds());
      case SemanticRegion.Condition condition ->
          new SemanticRegion.Condition(
              condition.regionId(),
              condition.ownerNodeId(),
              sortConditionBranches(condition.branches()),
              condition.reconvergenceNodeId());
      case SemanticRegion.Split split ->
          new SemanticRegion.Split(
              split.regionId(),
              split.ownerNodeId(),
              split.mode(),
              sortSplitBranches(split.branches()),
              split.reconvergenceNodeId());
      case SemanticRegion.Loop loop ->
          new SemanticRegion.Loop(
              loop.regionId(),
              loop.ownerNodeId(),
              loop.bodyEntryNodeId(),
              loop.bodyExitNodeIds(),
              loop.exitNodeId(),
              loop.policy());
      case SemanticRegion.Retry retry ->
          new SemanticRegion.Retry(
              retry.regionId(),
              retry.ownerNodeId(),
              retry.bodyEntryNodeId(),
              retry.bodyExitNodeIds(),
              retry.exhaustedNodeId(),
              retry.policy());
      case SemanticRegion.ErrorScope scope ->
          new SemanticRegion.ErrorScope(
              scope.regionId(),
              scope.ownerNodeId(),
              scope.tryEntryNodeId(),
              scope.handlers(),
              scope.finallyEntryNodeId(),
              scope.exitNodeIds());
    };
  }

  private static List<SemanticBranch.Condition> sortConditionBranches(
      List<SemanticBranch.Condition> branches) {
    List<SemanticBranch.Condition> copy = new ArrayList<>(branches);
    copy.sort(Comparator.comparing(SemanticBranch::branchId));
    return List.copyOf(copy);
  }

  private static List<SemanticBranch.Split> sortSplitBranches(List<SemanticBranch.Split> branches) {
    List<SemanticBranch.Split> copy = new ArrayList<>(branches);
    copy.sort(Comparator.comparing(SemanticBranch::branchId));
    return List.copyOf(copy);
  }

  private static List<SemanticExecutionEdge> sortEdges(List<SemanticExecutionEdge> edges) {
    List<SemanticExecutionEdge> copy = new ArrayList<>(edges.size());
    for (SemanticExecutionEdge edge : edges) {
      copy.add(
          new SemanticExecutionEdge(
              edge.edgeId(),
              edge.sourceNodeId(),
              edge.targetNodeId(),
              edge.regionId(),
              canonicalRoute(edge.route()),
              edge.mappingId()));
    }
    copy.sort(Comparator.comparing(SemanticExecutionEdge::edgeId));
    return List.copyOf(copy);
  }

  private static SemanticRoute canonicalRoute(SemanticRoute route) {
    if (route == null) {
      return null;
    }
    return switch (route) {
      case SemanticRoute.Sequence sequence -> sequence;
      case SemanticRoute.ConditionBranch branch -> branch;
      case SemanticRoute.SplitBranch branch -> branch;
      case SemanticRoute.Reconverge reconverge ->
          new SemanticRoute.Reconverge(sortStrings(reconverge.branchIds()));
      case SemanticRoute.LoopBody body -> body;
      case SemanticRoute.LoopExit exit -> exit;
      case SemanticRoute.RetryAttempt attempt -> attempt;
      case SemanticRoute.RetryExhausted exhausted -> exhausted;
      case SemanticRoute.TryPath tryPath -> tryPath;
      case SemanticRoute.CatchPath catchPath -> catchPath;
      case SemanticRoute.FinallyPath finallyPath -> finallyPath;
    };
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
