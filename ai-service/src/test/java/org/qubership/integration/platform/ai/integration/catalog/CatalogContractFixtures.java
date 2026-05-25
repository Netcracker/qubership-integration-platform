package org.qubership.integration.platform.ai.integration.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads classpath fixtures under {@code catalog-contract/}. */
public final class CatalogContractFixtures {

  private CatalogContractFixtures() {}

  public static String resourceUtf8(String classpathRelativePath) {
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathRelativePath)) {
      Objects.requireNonNull(in, "Missing classpath resource: " + classpathRelativePath);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
