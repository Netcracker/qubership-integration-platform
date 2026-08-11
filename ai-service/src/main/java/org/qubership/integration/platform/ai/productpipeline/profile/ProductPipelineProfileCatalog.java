package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves validated product-pipeline profiles by pinned id and version. */
public final class ProductPipelineProfileCatalog {

  private final Map<String, ProductPipelineProfile> profiles;

  public ProductPipelineProfileCatalog(Collection<ProductPipelineProfile> profiles) {
    this.profiles =
        Objects.requireNonNull(profiles, "profiles").stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    profile -> profile.profileId() + "@" + profile.profileVersion(),
                    Function.identity()));
  }

  public ProductPipelineProfile require(String profileId, String profileVersion) {
    ProductPipelineProfile profile = profiles.get(profileId + "@" + profileVersion);
    if (profile == null) {
      throw new IllegalArgumentException(
          "unknown product profile: " + profileId + "@" + profileVersion);
    }
    return profile;
  }
}
