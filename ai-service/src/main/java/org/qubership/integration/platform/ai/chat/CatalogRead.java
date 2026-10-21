package org.qubership.integration.platform.ai.chat;

import java.util.Optional;

/** Distinguishes a missing catalog value from a read that was not requested or did not complete. */
public record CatalogRead<T>(State state, T value) {

  public CatalogRead {
    state = state == null ? State.NOT_REQUESTED : state;
    if (state != State.AVAILABLE) {
      value = null;
    }
  }

  public static <T> CatalogRead<T> notRequested() {
    return new CatalogRead<>(State.NOT_REQUESTED, null);
  }

  public static <T> CatalogRead<T> unavailable() {
    return new CatalogRead<>(State.UNAVAILABLE, null);
  }

  public static <T> CatalogRead<T> available(T value) {
    return new CatalogRead<>(State.AVAILABLE, value);
  }

  public Optional<T> availableValue() {
    return state == State.AVAILABLE ? Optional.ofNullable(value) : Optional.empty();
  }

  public enum State {
    NOT_REQUESTED,
    AVAILABLE,
    UNAVAILABLE
  }
}
