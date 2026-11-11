package org.qubership.integration.platform.ai.productpipeline.runtime;

/**
 * Who the trusted adapter says produced this command. The transport does not prove a human typed
 * the text; only the adapter field does. Absent or untrusted origin uses the ledger's flat budget.
 */
public enum InputOrigin {
  /** No adapter recorded an origin. */
  ABSENT,
  /** The adapter does not assert a human typed this command. */
  UNTRUSTED,
  /** A trusted adapter recorded this command. */
  TRUSTED;

  public boolean isTrusted() {
    return this == TRUSTED;
  }

  public static InputOrigin of(InputOrigin origin) {
    return origin == null ? ABSENT : origin;
  }
}
