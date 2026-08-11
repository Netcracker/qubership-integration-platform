package org.qubership.integration.platform.ai.qipknowledge.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Rejects incompatible or corrupted certified knowledge exports at build time. */
public final class QipKnowledgeExportValidator {

  public static final Path EXPORT_RELATIVE_PATH =
      Path.of(
          ".apm",
          "skills",
          "cip-runtime-context-loader",
          "assets",
          "knowledge-export");

  private static final List<String> CAPABILITY_FILES =
      List.of(
          "capabilities/capability-catalog.yaml",
          "capabilities/capability-index.yaml",
          "capabilities/capability-relations.yaml");
  private static final List<String> REQUIRED_CHECKSUMS =
      List.of(
          "manifest.yaml",
          "objects.jsonl",
          "capabilities/capability-catalog.yaml",
          "capabilities/capability-index.yaml",
          "capabilities/capability-relations.yaml");
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final ObjectMapper JSON = new ObjectMapper();

  private QipKnowledgeExportValidator() {}

  public static void main(String[] args) throws Exception {
    String configured = System.getProperty("qip.knowledge.export");
    String path = args.length == 1 ? args[0] : configured;
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException(
          "Pass the knowledge-export directory as the only argument or set qip.knowledge.export");
    }
    System.out.println(JSON.writeValueAsString(validate(Path.of(path))));
  }

  public static ExportMetadata validatePack(Path packRoot) {
    return validate(packRoot.resolve(EXPORT_RELATIVE_PATH));
  }

  public static ExportMetadata validate(Path exportRoot) {
    Path root = exportRoot.toAbsolutePath().normalize();
    requireDirectory(root, "Knowledge export directory is missing");

    JsonNode manifest = readYaml(root.resolve("manifest.yaml"));
    requireText(manifest, "manifest_schema", "1.0");
    requireText(manifest, "product", "CIP");
    requireText(manifest, "runtime_sdk_version", ">=1.0.0 <2.0");
    requireMajor(manifest, "schema_version", 1);
    requireMajor(manifest, "relation_schema_version", 1);

    Map<String, String> checksums = readChecksums(root);
    validateCapabilities(root);

    JsonNode certification = readYaml(root.resolve("runtime-certification.yaml")).path("certification");
    requireObject(certification, "certification");
    requireText(certification, "status", "CERTIFIED");
    JsonNode certifiedPackage = certification.path("package");
    requireObject(certifiedPackage, "certification.package");
    String packageChecksum = requiredText(manifest.path("integrity"), "package_checksum");
    if (!packageChecksum.equals(requiredText(certifiedPackage, "manifest_hash"))) {
      fail("Certification manifest hash does not match the package checksum");
    }

    int totalObjects = requiredInt(manifest, "total_objects");
    if (requiredInt(certification, "total_objects") != totalObjects) {
      fail("Certification object count does not match the manifest");
    }
    validateObjects(root.resolve("objects.jsonl"), totalObjects);

    return new ExportMetadata(
        requiredText(certifiedPackage, "key"),
        requiredText(manifest, "knowledge_version"),
        requiredText(manifest, "compiler_version"),
        requiredText(manifest, "schema_version"),
        requiredText(manifest, "runtime_sdk_version"),
        packageChecksum,
        requiredText(certification, "status"),
        totalObjects,
        checksums.size());
  }

  private static Map<String, String> readChecksums(Path root) {
    Path checksumFile = root.resolve("CHECKSUMS.sha256");
    List<String> lines;
    try {
      lines = Files.readAllLines(checksumFile, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalArgumentException("Cannot read CHECKSUMS.sha256", error);
    }

    Map<String, String> checksums = new HashMap<>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      int separator = line.indexOf("  ");
      String digest = separator < 0 ? "" : line.substring(0, separator);
      String name = separator < 0 ? "" : line.substring(separator + 2);
      Path relative = Path.of(name).normalize();
      if (separator < 0
          || !digest.matches("[0-9a-f]{64}")
          || name.isBlank()
          || relative.isAbsolute()
          || relative.startsWith("..")
          || checksums.putIfAbsent(name, digest) != null) {
        fail("Invalid checksum entry at line " + (index + 1));
      }
    }
    for (String required : REQUIRED_CHECKSUMS) {
      if (!checksums.containsKey(required)) {
        fail("Missing checksum entry: " + required);
      }
    }
    checksums.forEach(
        (name, expected) -> {
          Path target = root.resolve(name).normalize();
          if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            fail("Missing package file: " + name);
          }
          if (!expected.equals(sha256(target))) {
            fail("Checksum mismatch for " + name);
          }
        });
    return checksums;
  }

  private static void validateCapabilities(Path root) {
    JsonNode catalog = readYaml(root.resolve(CAPABILITY_FILES.get(0)));
    JsonNode capabilities = catalog.path("capabilities");
    if (!capabilities.isArray()) {
      fail("capability-catalog.yaml has no capabilities list");
    }

    JsonNode tokenMap =
        readYaml(root.resolve(CAPABILITY_FILES.get(1))).path("capability_index").path("token_to_capability");
    requireObject(tokenMap, "token_to_capability");
    if (tokenMap.isEmpty()) {
      fail("token_to_capability must not be empty");
    }

    JsonNode relationMap =
        readYaml(root.resolve(CAPABILITY_FILES.get(2))).path("capability_relations");
    requireObject(relationMap, "capability_relations");
    Set<String> relationNames = new HashSet<>();
    relationMap.fieldNames().forEachRemaining(name -> relationNames.add(name.toLowerCase(Locale.ROOT)));
    relationMap.fields().forEachRemaining(
        entry -> {
          JsonNode contains = entry.getValue().path("contains");
          if (!contains.isArray()) {
            fail("Capability " + entry.getKey() + " has an invalid contains list");
          }
          contains.forEach(
              objectId -> {
                if (!objectId.isTextual() || objectId.textValue().isBlank()) {
                  fail("Capability " + entry.getKey() + " has an invalid contains list");
                }
              });
        });
    tokenMap.fields().forEachRemaining(
        entry -> {
          if (entry.getKey().isBlank()
              || !entry.getValue().isTextual()
              || entry.getValue().textValue().isBlank()) {
            fail("token_to_capability has invalid entries");
          }
          if (!relationNames.contains(entry.getValue().textValue().toLowerCase(Locale.ROOT))) {
            fail("Capability has no relation entry: " + entry.getValue().textValue());
          }
        });
  }

  private static void validateObjects(Path objectsFile, int expectedCount) {
    Set<String> ids = new HashSet<>();
    try (var lines = Files.lines(objectsFile, StandardCharsets.UTF_8)) {
      int[] lineNumber = {0};
      lines.forEach(
          line -> {
            lineNumber[0]++;
            try {
              JsonNode object = JSON.readTree(line);
              String id = requiredText(object, "id");
              if (!ids.add(id)) {
                fail("Duplicate canonical ID: " + id);
              }
            } catch (IOException error) {
              throw new IllegalArgumentException(
                  "Invalid objects.jsonl at line " + lineNumber[0], error);
            }
          });
    } catch (IOException error) {
      throw new IllegalArgumentException("Cannot read objects.jsonl", error);
    }
    if (ids.size() != expectedCount) {
      fail("Expected " + expectedCount + " objects, found " + ids.size());
    }
  }

  private static JsonNode readYaml(Path path) {
    if (!Files.isRegularFile(path)) {
      fail("Missing package file: " + path.getFileName());
    }
    try {
      JsonNode node = YAML.readTree(path.toFile());
      requireObject(node, path.getFileName().toString());
      return node;
    } catch (IOException error) {
      throw new IllegalArgumentException("Cannot read " + path.getFileName(), error);
    }
  }

  private static String sha256(Path path) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (IOException | NoSuchAlgorithmException error) {
      throw new IllegalArgumentException("Cannot hash " + path.getFileName(), error);
    }
  }

  private static void requireDirectory(Path path, String message) {
    if (!Files.isDirectory(path)) {
      fail(message + ": " + path);
    }
  }

  private static void requireObject(JsonNode node, String name) {
    if (node == null || !node.isObject()) {
      fail(name + " must be a mapping");
    }
  }

  private static void requireText(JsonNode node, String field, String expected) {
    String actual = requiredText(node, field);
    if (!expected.equals(actual)) {
      fail(field + " must be " + expected + ", found " + actual);
    }
  }

  private static void requireMajor(JsonNode node, String field, int expected) {
    String value = requiredText(node, field);
    try {
      if (Integer.parseInt(value.split("\\.", 2)[0]) != expected) {
        fail(field + " major must be " + expected);
      }
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " is invalid: " + value, error);
    }
  }

  private static String requiredText(JsonNode node, String field) {
    requireObject(node, "Parent of " + field);
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      fail(field + " must be a non-empty string");
    }
    return value.textValue();
  }

  private static int requiredInt(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isInt() || value.intValue() < 0) {
      fail(field + " must be a non-negative integer");
    }
    return value.intValue();
  }

  private static void fail(String message) {
    throw new IllegalArgumentException("Invalid certified knowledge export: " + message);
  }

  public record ExportMetadata(
      String packageKey,
      String knowledgeVersion,
      String compilerVersion,
      String schemaVersion,
      String runtimeSdkVersion,
      String packageChecksum,
      String certificationStatus,
      int totalObjects,
      int checksummedFiles) {}
}
