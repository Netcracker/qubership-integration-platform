package org.qubership.integration.platform.ai.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** Enables CREATE and EDIT only when their complete private instruction package is installed. */
@ApplicationScoped
public class RockySkills {
  public static final String UNAVAILABLE =
      "CREATE and EDIT require the private rocky-skills package. Other Rocky features remain available.";
  private static final String ROOT = "META-INF/rocky-skills/";
  private static final Set<String> REQUIRED = Set.of(
      "brainstorming", "cip-chain-edit-planner", "cip-conf-requirement-analysis", "cip-conf-semantic-design");
  private final ClassLoader loader;
  private volatile Map<String, String> instructions;

  public RockySkills() {
    this(RockySkills.class.getClassLoader());
  }

  public RockySkills(ClassLoader loader) {
    this.loader = loader;
  }

  public boolean available() {
    if (instructions == null) {
      synchronized (this) {
        if (instructions == null) instructions = load();
      }
    }
    return !instructions.isEmpty();
  }

  public void requireAvailable() {
    if (!available()) throw new IllegalStateException(UNAVAILABLE);
  }

  public String instructions(String skillId) {
    requireAvailable();
    String text = instructions.get(skillId);
    if (text == null) throw new IllegalArgumentException("Unknown Rocky skill: " + skillId);
    // Package metadata is not part of the model's instructions.
    return text.replaceFirst("(?s)\\A---\\R.*?\\R---\\R", "");
  }

  private Map<String, String> load() {
    try {
      var manifest = new ObjectMapper().readTree(read(ROOT + "manifest.json"));
      if (manifest.path("schemaVersion").asInt() != 1
          || !"rocky-skills".equals(manifest.path("package").asText())
          || !"integration-platform-skills".equals(manifest.path("runtimeCompatibilityId").asText())) {
        return Map.of();
      }
      Map<String, String> loaded = new HashMap<>();
      for (var id : manifest.path("activePromptIds")) {
        String name = id.asText();
        if (!name.matches("[a-z0-9-]+") || loaded.containsKey(name)) return Map.of();
        String path = "skills/" + name + "/SKILL.md";
        byte[] bytes = read(ROOT + path);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!hash.equals(manifest.path("files").path(path).path("sha256").asText())
            || bytes.length == 0) return Map.of();
        loaded.put(name, new String(bytes, StandardCharsets.UTF_8));
      }
      if (!loaded.keySet().containsAll(REQUIRED)) return Map.of();
      for (String index : Set.of("qip-knowledge-pack-manifest.json", "capability-registry.json", "compiler-pipeline-index.json")) {
        if (read("qipknowledge/integration-platform-skills/" + index).length == 0) return Map.of();
      }
      return Map.copyOf(loaded);
    } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
      return Map.of();
    }
  }

  private byte[] read(String path) throws IOException {
    try (InputStream stream = loader.getResourceAsStream(path)) {
      if (stream == null) throw new IOException("Missing Rocky resource: " + path);
      return stream.readAllBytes();
    }
  }
}
