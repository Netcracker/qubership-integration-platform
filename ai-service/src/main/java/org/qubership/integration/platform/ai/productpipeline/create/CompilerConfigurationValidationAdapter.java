package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;

/** Adapter id binding for cip-configuration-validator scheduler node. */
@ApplicationScoped
public class CompilerConfigurationValidationAdapter extends AbstractNoopValidationAdapter {

  @Override
  public String adapterId() {
    return CompilerValidationPipeline.CONFIGURATION;
  }
}
