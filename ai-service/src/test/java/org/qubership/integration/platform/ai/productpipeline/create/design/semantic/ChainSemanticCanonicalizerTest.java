package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;

class ChainSemanticCanonicalizerTest {

  private final ChainSemanticCanonicalizer canonicalizer = new ChainSemanticCanonicalizer();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void sha256IsStableAcrossWriteThenRead() throws Exception {
    assertEquals(
        canonicalizer.sha256(read("semantic-revision.json")),
        canonicalizer.sha256(writeThenRead("semantic-revision.json")));
  }

  @Test
  void sha256IgnoresUnorderedCollectionOrder() {
    ChainSemanticRevision revision = twoEntryRevision();
    List<SemanticNode> shuffledNodes = new ArrayList<>(revision.nodes());
    Collections.reverse(shuffledNodes);
    List<SemanticEntryPoint> shuffledEntries = new ArrayList<>(revision.entryPoints());
    Collections.reverse(shuffledEntries);
    ChainSemanticRevision shuffled =
        copy(
            revision,
            shuffledEntries,
            shuffledNodes,
            revision.executionEdges(),
            revision.mappingIntents(),
            revision.constraints());
    assertEquals(canonicalizer.sha256(revision), canonicalizer.sha256(shuffled));
  }

  @Test
  void canonicalEntryPointsAreSortedByIdentityNotPresentationOrder() throws Exception {
    ChainSemanticRevision base = twoEntryRevision();
    SemanticEntryPoint http = withOrder(base.entryPoints().get(0), 1);
    SemanticEntryPoint kafka = withOrder(base.entryPoints().get(1), 0);
    ChainSemanticRevision revision =
        copy(
            base,
            List.of(kafka, http),
            base.nodes(),
            base.executionEdges(),
            base.mappingIntents(),
            base.constraints());
    JsonNode entries = mapper.readTree(canonicalizer.canonicalBytes(revision)).get("entryPoints");
    assertEquals("http-in", entries.get(0).get("entryPointId").asText());
    assertEquals(1, entries.get(0).get("order").asInt());
    assertEquals("kafka-in", entries.get(1).get("entryPointId").asText());
    assertEquals(0, entries.get(1).get("order").asInt());
  }

  @Test
  void sha256ChangesWhenConsumedFieldsChange() {
    ChainSemanticRevision base = twoEntryRevision();
    String hash = canonicalizer.sha256(base);
    assertNotEquals(
        hash,
        canonicalizer.sha256(
            copy(
                base,
                base.entryPoints(),
                base.nodes(),
                base.executionEdges(),
                base.mappingIntents(),
                List.of("must retry"))));
    MappingIntent mapping = base.mappingIntents().get(0);
    MappingIntent renamed =
        new MappingIntent(
            "map-body-2",
            mapping.sourceRef(),
            mapping.sourcePort(),
            mapping.targetRef(),
            mapping.targetPort(),
            mapping.rules());
    assertNotEquals(
        hash,
        canonicalizer.sha256(
            copy(
                base,
                base.entryPoints(),
                base.nodes(),
                base.executionEdges(),
                List.of(renamed),
                base.constraints())));
    SemanticExecutionEdge edge = base.executionEdges().get(2);
    SemanticExecutionEdge retargeted =
        new SemanticExecutionEdge(
            edge.edgeId(),
            edge.sourceNodeId(),
            "trigger-http",
            edge.regionId(),
            edge.route(),
            edge.mappingId());
    List<SemanticExecutionEdge> edges =
        List.of(base.executionEdges().get(0), base.executionEdges().get(1), retargeted);
    assertNotEquals(
        hash,
        canonicalizer.sha256(
            copy(
                base,
                base.entryPoints(),
                base.nodes(),
                edges,
                base.mappingIntents(),
                base.constraints())));
    SemanticEntryPoint http = base.entryPoints().get(0);
    SemanticEntryPoint retargetedEntry =
        new SemanticEntryPoint(
            http.entryPointId(),
            http.triggerNodeId(),
            "node-call",
            http.order(),
            http.provenance(),
            http.presentation());
    assertNotEquals(
        hash,
        canonicalizer.sha256(
            copy(
                base,
                List.of(retargetedEntry, base.entryPoints().get(1)),
                base.nodes(),
                base.executionEdges(),
                base.mappingIntents(),
                base.constraints())));
  }

  @Test
  void dedicatedMapperKeepsNullFieldsInCanonicalBytes() {
    String json =
        new String(canonicalizer.canonicalBytes(twoEntryRevision()), StandardCharsets.UTF_8);
    assertTrue(json.contains("\"regionId\":null"));
    assertTrue(json.contains("\"route\":null"));
    assertEquals(
        canonicalizer.sha256(twoEntryRevision()),
        new ChainSemanticCanonicalizer().sha256(twoEntryRevision()));
  }

  private ChainSemanticRevision read(String resource) throws Exception {
    try (InputStream stream = getClass().getResourceAsStream(resource)) {
      assertNotNull(stream, resource);
      return mapper.readValue(stream, ChainSemanticRevision.class);
    }
  }

  private ChainSemanticRevision writeThenRead(String resource) throws Exception {
    ChainSemanticRevision revision = read(resource);
    return mapper.readValue(canonicalizer.canonicalBytes(revision), ChainSemanticRevision.class);
  }

  private static ChainSemanticRevision twoEntryRevision() {
    return SemanticFixtures.revision(
        List.of(
            SemanticFixtures.entry("http-in", "trigger-http"),
            SemanticFixtures.entry("kafka-in", "trigger-kafka")));
  }

  private static SemanticEntryPoint withOrder(SemanticEntryPoint source, int order) {
    return new SemanticEntryPoint(
        source.entryPointId(),
        source.triggerNodeId(),
        source.initialTargetNodeId(),
        order,
        source.provenance(),
        source.presentation());
  }

  private static ChainSemanticRevision copy(
      ChainSemanticRevision base,
      List<SemanticEntryPoint> entryPoints,
      List<SemanticNode> nodes,
      List<SemanticExecutionEdge> edges,
      List<MappingIntent> mappings,
      List<String> constraints) {
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        entryPoints,
        nodes,
        base.regions(),
        edges,
        base.containment(),
        mappings,
        constraints,
        base.assumptions(),
        base.citations());
  }
}
