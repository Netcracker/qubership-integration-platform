package org.qubership.integration.platform.ai.productpipeline.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackRepository;

/** Derives {@code profileId@profileVersion} keys from pack product-pipeline profile YAML. */
public final class ProductPipelineProfileKeys {

  private ProductPipelineProfileKeys() {}

  public static String key(ProductPipelineProfile profile) {
    Objects.requireNonNull(profile, "profile");
    return profile.profileId() + "@" + profile.profileVersion();
  }

  public static Map<String, ProductPipelineProfile> loadFromProfilesDirectory(Path profilesDir)
      throws IOException {
    if (profilesDir == null || !Files.isDirectory(profilesDir)) {
      return Map.of();
    }
    Map<String, ProductPipelineProfile> profiles = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(profilesDir)) {
      files
          .filter(path -> path.getFileName().toString().endsWith(".yaml"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(
              path -> {
                try (InputStream input = Files.newInputStream(path)) {
                  ProductPipelineProfile profile = ProductPipelineProfileParser.parse(input);
                  profiles.put(key(profile), profile);
                } catch (IOException e) {
                  throw new IllegalStateException("cannot read product profile " + path, e);
                }
              });
    }
    return Map.copyOf(profiles);
  }

  public static List<String> readFromProfilesDirectory(Path profilesDir) throws IOException {
    if (profilesDir == null || !Files.isDirectory(profilesDir)) {
      return List.of();
    }
    List<String> keys = new ArrayList<>();
    try (Stream<Path> files = Files.list(profilesDir)) {
      files
          .filter(path -> path.getFileName().toString().endsWith(".yaml"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(
              path -> {
                try (InputStream input = Files.newInputStream(path)) {
                  keys.add(key(ProductPipelineProfileParser.parse(input)));
                } catch (IOException e) {
                  throw new IllegalStateException("cannot read product profile " + path, e);
                }
              });
    }
    return List.copyOf(keys);
  }

  public static List<String> readFromPackRepository(CipKnowledgePackRepository packRepository) {
    Objects.requireNonNull(packRepository, "packRepository");
    List<String> keys = new ArrayList<>();
    for (String relativePath : packRepository.loadProductPipelineProfileResources()) {
      try (InputStream input = packRepository.openProductPipelineResource(relativePath)) {
        if (input == null) {
          throw new IllegalStateException("missing pack product profile resource: " + relativePath);
        }
        keys.add(key(ProductPipelineProfileParser.parse(input)));
      } catch (IOException e) {
        throw new IllegalStateException("cannot read pack product profile " + relativePath, e);
      }
    }
    return List.copyOf(keys);
  }
}
