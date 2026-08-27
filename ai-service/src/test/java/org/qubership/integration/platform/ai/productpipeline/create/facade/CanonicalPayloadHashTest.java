package org.qubership.integration.platform.ai.productpipeline.create.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.QipKnowledgeCitation;
import org.qubership.integration.platform.ai.qipknowledge.knowledge.QipKnowledgeRefType;

class CanonicalPayloadHashTest {

  @Test
  void digestIs64LowercaseHexCharacters() {
    String digest = CanonicalPayloadHash.sha256Hex(Map.of("chainId", "c1", "outcome", "materialized"));
    assertEquals(64, digest.length());
    assertTrue(digest.matches("[0-9a-f]{64}"));
  }

  @Test
  void equalPayloadsWithDifferentInsertionOrderShareDigest() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("chainId", "chain-1");
    a.put("chainName", "Greetings");
    a.put("outcome", "materialized");
    a.put("status", "DRAFT");

    Map<String, Object> b = new LinkedHashMap<>();
    b.put("status", "DRAFT");
    b.put("outcome", "materialized");
    b.put("chainName", "Greetings");
    b.put("chainId", "chain-1");

    assertEquals(CanonicalPayloadHash.sha256Hex(a), CanonicalPayloadHash.sha256Hex(b));
  }

  @Test
  void fieldChangesChangeDigest() {
    Map<String, Object> base = new LinkedHashMap<>();
    base.put("chainId", "chain-1");
    base.put("chainName", "Greetings");
    base.put("outcome", "materialized");
    base.put("status", "DRAFT");
    String baseDigest = CanonicalPayloadHash.sha256Hex(base);

    Map<String, Object> chainIdChanged = new LinkedHashMap<>(base);
    chainIdChanged.put("chainId", "chain-2");
    assertNotEquals(baseDigest, CanonicalPayloadHash.sha256Hex(chainIdChanged));

    Map<String, Object> nameChanged = new LinkedHashMap<>(base);
    nameChanged.put("chainName", "Other");
    assertNotEquals(baseDigest, CanonicalPayloadHash.sha256Hex(nameChanged));

    Map<String, Object> statusChanged = new LinkedHashMap<>(base);
    statusChanged.put("status", "PUBLISHED");
    assertNotEquals(baseDigest, CanonicalPayloadHash.sha256Hex(statusChanged));

    Map<String, Object> outcomeChanged = new LinkedHashMap<>(base);
    outcomeChanged.put("outcome", "failed");
    assertNotEquals(baseDigest, CanonicalPayloadHash.sha256Hex(outcomeChanged));
  }

  @Test
  void matchesTestVectorFromCanonicalJson() {
    String canonical =
        "{\"chainId\":\"chain-1\",\"chainName\":\"Greetings\",\"outcome\":\"materialized\",\"status\":\"DRAFT\"}";
    assertEquals(CanonicalPayloadHash.sha256Hex(canonical), CanonicalPayloadHash.sha256Hex(canonical));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("chainId", "chain-1");
    payload.put("chainName", "Greetings");
    payload.put("outcome", "materialized");
    payload.put("status", "DRAFT");
    assertEquals(CanonicalPayloadHash.sha256Hex(canonical), CanonicalPayloadHash.sha256Hex(payload));
  }

  @Test
  void semanticDigestDelegatesToChainSemanticCanonicalizer() {
    ChainSemanticRevision revision = twoEntryRevision();
    assertEquals(
        new ChainSemanticCanonicalizer().sha256(revision),
        CanonicalPayloadHash.sha256Hex(revision));
  }

  @Test
  void semanticDigestChangesWhenConsumedFieldsChange() {
    ChainSemanticRevision revision = twoEntryRevision();
    String digest = CanonicalPayloadHash.sha256Hex(revision);
    assertNotEquals(digest, CanonicalPayloadHash.sha256Hex(withChangedEntryPoint(revision)));
    assertNotEquals(digest, CanonicalPayloadHash.sha256Hex(withChangedEdge(revision)));
    assertNotEquals(digest, CanonicalPayloadHash.sha256Hex(withChangedMapping(revision)));
    assertNotEquals(
        digest, CanonicalPayloadHash.sha256Hex(withChangedProvenanceCitation(revision)));
  }

  private static ChainSemanticRevision twoEntryRevision() {
    return SemanticFixtures.revision(
        List.of(
            SemanticFixtures.entry("http-in", "trigger-http"),
            SemanticFixtures.entry("kafka-in", "trigger-kafka")));
  }

  private static ChainSemanticRevision withChangedEntryPoint(ChainSemanticRevision revision) {
    SemanticEntryPoint http = revision.entryPoints().getFirst();
    SemanticEntryPoint retargeted =
        new SemanticEntryPoint(
            http.entryPointId(),
            http.triggerNodeId(),
            "node-call",
            http.order(),
            http.provenance(),
            http.presentation());
    return copy(
        revision,
        List.of(retargeted, revision.entryPoints().get(1)),
        revision.executionEdges(),
        revision.mappingIntents(),
        revision.citations());
  }

  private static ChainSemanticRevision withChangedEdge(ChainSemanticRevision revision) {
    SemanticExecutionEdge edge = revision.executionEdges().getLast();
    SemanticExecutionEdge retargeted =
        new SemanticExecutionEdge(
            edge.edgeId(),
            edge.sourceNodeId(),
            "trigger-http",
            edge.regionId(),
            edge.route(),
            edge.mappingId());
    List<SemanticExecutionEdge> edges = new ArrayList<>(revision.executionEdges());
    edges.set(edges.size() - 1, retargeted);
    return copy(
        revision, revision.entryPoints(), edges, revision.mappingIntents(), revision.citations());
  }

  private static ChainSemanticRevision withChangedMapping(ChainSemanticRevision revision) {
    MappingIntent mapping = revision.mappingIntents().getFirst();
    MappingIntent renamed =
        new MappingIntent(
            "map-body-changed",
            mapping.sourceRef(),
            mapping.sourcePort(),
            mapping.targetRef(),
            mapping.targetPort(),
            mapping.rules());
    return copy(
        revision,
        revision.entryPoints(),
        revision.executionEdges(),
        List.of(renamed),
        revision.citations());
  }

  private static ChainSemanticRevision withChangedProvenanceCitation(
      ChainSemanticRevision revision) {
    return copy(
        revision,
        revision.entryPoints(),
        revision.executionEdges(),
        revision.mappingIntents(),
        List.of(
            new QipKnowledgeCitation(
                "cite-1", QipKnowledgeRefType.RULE, "rules/example.yaml", null, "pinned fact")));
  }

  private static ChainSemanticRevision copy(
      ChainSemanticRevision base,
      List<SemanticEntryPoint> entryPoints,
      List<SemanticExecutionEdge> edges,
      List<MappingIntent> mappings,
      List<QipKnowledgeCitation> citations) {
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        entryPoints,
        base.nodes(),
        base.regions(),
        edges,
        base.containment(),
        mappings,
        base.constraints(),
        base.assumptions(),
        citations);
  }
}
