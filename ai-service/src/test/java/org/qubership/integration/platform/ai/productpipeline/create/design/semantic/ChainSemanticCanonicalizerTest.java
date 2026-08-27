package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    ChainSemanticRevision revision =
        SemanticFixtures.revision(
            List.of(
                new SemanticEntryPoint("http-in", "trigger-http"),
                new SemanticEntryPoint("kafka-in", "trigger-kafka")));
    List<SemanticNode> shuffledNodes = new ArrayList<>(revision.nodes());
    Collections.reverse(shuffledNodes);
    ChainSemanticRevision shuffled =
        new ChainSemanticRevision(
            revision.schemaVersion(),
            revision.revisionId(),
            revision.chainIdentity(),
            revision.compilerContractVersion(),
            revision.entryPoints(),
            shuffledNodes,
            revision.regions(),
            revision.executionEdges(),
            revision.containment(),
            revision.mappingIntents(),
            revision.constraints(),
            revision.assumptions(),
            revision.citations());
    assertEquals(canonicalizer.sha256(revision), canonicalizer.sha256(shuffled));
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
}
