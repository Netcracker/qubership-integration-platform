package org.qubership.integration.platform.ai.productpipeline.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Parses product-pipeline profile YAML into an immutable {@link ProductPipelineProfile}. */
public final class ProductPipelineProfileParser {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private ProductPipelineProfileParser() {}

  public static ProductPipelineProfile parse(InputStream input) {
    if (input == null) {
      throw new IllegalArgumentException("profile input stream is required");
    }
    try {
      return YAML.readValue(input, ProductPipelineProfile.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to parse product-pipeline profile YAML", e);
    }
  }
}
