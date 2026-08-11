package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;

/** Adapter id binding for cip-quality-validator scheduler node. */
@ApplicationScoped
public class CompilerQualityValidationAdapter extends AbstractNoopValidationAdapter {

  @Override
  public String adapterId() {
    return CompilerValidationPipeline.QUALITY;
  }
}
