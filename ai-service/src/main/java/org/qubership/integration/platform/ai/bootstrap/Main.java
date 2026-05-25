package org.qubership.integration.platform.ai.bootstrap;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * QIP AI Service entry point.
 *
 * <p>This service provides an AI-powered chat interface for the Qubership Integration Platform. It
 * exposes a streaming SSE endpoint at {@code POST /api/v1/chat} and handles several scenarios:
 *
 * <ol>
 *   <li>Create Integration Design Specification (IDS) from text
 *   <li>Query an existing design document
 *   <li>Implement a QIP chain from a design document
 *   <li>Compare a new design with an existing chain and apply changes
 *   <li>Reverse-engineer a QIP chain into a design document
 *   <li>Generate test cases from a chain or design
 *   <li>Generate a Postman Collection from test cases
 * </ol>
 */
@QuarkusMain
public class Main implements QuarkusApplication {

  public static void main(String... args) {
    Quarkus.run(Main.class, args);
  }

  @Override
  public int run(String... args) throws Exception {
    Quarkus.waitForExit();
    return 0;
  }
}
