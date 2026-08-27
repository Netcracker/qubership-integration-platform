package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChainSemanticRevisionTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void roundTripsRevisionWithTwoEntryPoints() throws Exception {
    ChainSemanticRevision revision =
        SemanticFixtures.revision(
            List.of(
                new SemanticEntryPoint("http-in", "trigger-http"),
                new SemanticEntryPoint("kafka-in", "trigger-kafka")));
    assertEquals(revision, roundTrip(revision));
    assertEquals(2, roundTrip(revision).entryPoints().size());
  }

  @Test
  void rejectsUnsupportedSchemaVersion() {
    ChainSemanticRevision valid =
        SemanticFixtures.revision(List.of(new SemanticEntryPoint("http-in", "trigger-http")));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ChainSemanticRevision(
                    "normalized-design-flow/v1",
                    valid.revisionId(),
                    valid.chainIdentity(),
                    valid.compilerContractVersion(),
                    valid.entryPoints(),
                    valid.nodes(),
                    valid.regions(),
                    valid.executionEdges(),
                    valid.containment(),
                    valid.mappingIntents(),
                    valid.constraints(),
                    valid.assumptions(),
                    valid.citations()));
    assertEquals(
        "Unsupported semantic schema version: normalized-design-flow/v1", error.getMessage());
  }

  private ChainSemanticRevision roundTrip(ChainSemanticRevision revision) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(revision), ChainSemanticRevision.class);
  }
}
