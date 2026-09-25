package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.databind.JsonNode;

/** A named JSON constant carried by a descriptive mapping rule. */
public record MappingConstant(String name, JsonNode value) {

  public MappingConstant {
    name = name == null ? "" : name;
  }
}
