package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;

/** Adapter id binding for cip-element-validator scheduler node. */
@ApplicationScoped
public class CompilerElementValidationAdapter extends AbstractNoopValidationAdapter {

  @Override
  public String adapterId() {
    return CompilerValidationPipeline.ELEMENT;
  }
}
