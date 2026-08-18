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
  ONE;

  /** Inclusive lower bound on direct children of this type. */
  public int minimum() {
    return switch (this) {
      case ANY, ONE_OR_ZERO -> 0;
      case ONE, ONE_OR_MANY -> 1;
      case TWO_OR_MANY -> 2;
    };
  }

  /**
   * Inclusive upper bound on direct children of this type, or {@code null} when unbounded.
   */
  public Integer maximum() {
    return switch (this) {
      case ONE, ONE_OR_ZERO -> 1;
      case ANY, ONE_OR_MANY, TWO_OR_MANY -> null;
    };
  }
}
