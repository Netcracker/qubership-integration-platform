package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.Collection;
import java.util.Set;

/** Supported artifact {@code (type, schemaVersion)} pairs known to the runtime. */
public final class ArtifactSchemaRegistry {

  private final Set<ArtifactTypeRef> supported;

  public ArtifactSchemaRegistry(Collection<ArtifactTypeRef> supported) {
    this.supported = Set.copyOf(supported);
  }

  public static ArtifactSchemaRegistry of(ArtifactTypeRef... refs) {
    return new ArtifactSchemaRegistry(Set.of(refs));
  }

  public boolean supports(ArtifactTypeRef ref) {
    return supported.contains(ref);
  }
}
