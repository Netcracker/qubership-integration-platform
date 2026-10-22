package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Lookup of pinned stage capabilities by ID. */
public final class StageCapabilityRegistry {

  private final Map<String, StageCapability> byId;

  public StageCapabilityRegistry(Collection<StageCapability> capabilities) {
    this.byId =
        Objects.requireNonNull(capabilities, "capabilities").stream()
            .collect(Collectors.toUnmodifiableMap(StageCapability::capabilityId, Function.identity()));
  }

  public Optional<StageCapability> find(String capabilityId) {
    return Optional.ofNullable(byId.get(capabilityId));
  }

  public StageCapability require(String capabilityId) {
    return find(capabilityId)
        .orElseThrow(() -> new IllegalArgumentException("unknown capability: " + capabilityId));
  }
}
