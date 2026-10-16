package org.qubership.integration.platform.ai.productpipeline.create;

import jakarta.enterprise.context.ApplicationScoped;

/** Adapter id binding for cip-security-validator scheduler node. */
@ApplicationScoped
public class CompilerSecurityValidationAdapter extends AbstractNoopValidationAdapter {

  @Override
  public String adapterId() {
    return CompilerValidationPipeline.SECURITY;
  }
}
