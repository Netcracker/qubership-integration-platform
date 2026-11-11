package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;

/** Adapter id binding for cip-structural-validator scheduler node. */
@ApplicationScoped
public class CompilerStructuralValidationAdapter extends AbstractNoopValidationAdapter {

  @Override
  public String adapterId() {
    return CompilerValidationPipeline.STRUCTURAL;
  }
}
