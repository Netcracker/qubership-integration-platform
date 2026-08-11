package org.qubership.integration.platform.ai.productpipeline.profile;

/** Raised when a product-pipeline profile violates its contract. */
public final class ProductPipelineProfileValidationException extends RuntimeException {

  public ProductPipelineProfileValidationException(String message) {
    super(message);
  }
}
