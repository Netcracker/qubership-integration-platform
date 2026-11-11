package org.qubership.integration.platform.ai.plan.workdocument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Shared source and answer fixtures for offline filling and checkpoint tests. */
public final class WorkFillingFixtures {

  private WorkFillingFixtures() {}

  public static String read(String name) {
    try (var stream = WorkFillingFixtures.class.getResourceAsStream("/workdocument/" + name)) {
      if (stream == null) {
        throw new IllegalStateException("Test fixture is missing: " + name);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read test fixture: " + name, failure);
    }
  }
}
