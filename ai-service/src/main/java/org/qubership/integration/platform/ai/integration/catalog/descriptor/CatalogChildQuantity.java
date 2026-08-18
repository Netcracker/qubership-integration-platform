package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Child-count constraint from a catalog {@code Quantity} JSON value. */
public enum CatalogChildQuantity {
  @JsonProperty("any")
  ANY,
  @JsonProperty("one-or-zero")
  ONE_OR_ZERO,
  @JsonProperty("one-or-many")
  ONE_OR_MANY,
  @JsonProperty("two-or-many")
  TWO_OR_MANY,
  @JsonProperty("one")
  ONE
}
