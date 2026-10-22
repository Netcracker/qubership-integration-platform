package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;

/**
 * Guards the field that made structure capture fail on a duplicated key.
 *
 * <p>{@link ChainStructure} used to declare its own integer {@code schemaVersion} next to the
 * {@code "1.0"} string on the nested graph. Models merged the two names and emitted the key twice
 * inside {@code graph}; a record cannot bind a creator property that arrives after construction, so
 * capture aborted, and the repair path does not retry argument failures.
 */
class DuplicateToolArgumentBindingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String GRAPH = """
      "graph":{"schemaVersion":"1.0","chain":{"name":"c","description":"d"},
               "nodes":[],"edges":[]}
      """;

  @Test
  void captureExposesNoVersionFieldOfItsOwn() {
    List<String> names =
        java.util.Arrays.stream(ChainStructure.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();

    assertEquals(
        List.of("graph", "sourceRequirementFactIds", "knowledgeCitations", "subgraph"), names);
  }

  @Test
  void staleCaptureWithTheRemovedVersionStillBinds() throws Exception {
    ChainStructure capture =
        MAPPER.readValue("{\"schemaVersion\":1," + GRAPH + "}", ChainStructure.class);

    assertNotNull(capture.graph());
    assertEquals("1.0", capture.graph().schemaVersion());
  }

  @Test
  void duplicateKeyInsideGraphStillCannotBind() {
    String duplicated =
        "{" + GRAPH.stripTrailing().substring(0, GRAPH.stripTrailing().length() - 1)
            + ",\"schemaVersion\":1}}";

    assertThrows(Exception.class, () -> MAPPER.readValue(duplicated, ChainStructure.class));
  }
}
