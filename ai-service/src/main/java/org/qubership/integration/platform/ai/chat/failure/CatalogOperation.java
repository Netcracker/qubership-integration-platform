package org.qubership.integration.platform.ai.chat.failure;

/** Catalog operation whose verb prefixes a mapped HTTP 400. */
public enum CatalogOperation {
  SNAPSHOT("take a catalog snapshot"),
  DEPLOY("deploy this chain"),
  UNDEPLOY("undeploy this chain"),
  STATUS("read deployment status"),
  LOGGING("save session logging"),
  FACTS("load chain facts"),
  LOOKUP("find that chain");

  private final String verb;

  CatalogOperation(String verb) {
    this.verb = verb;
  }

  String verb() {
    return verb;
  }
}
