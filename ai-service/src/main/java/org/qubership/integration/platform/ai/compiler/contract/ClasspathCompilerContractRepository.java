package org.qubership.integration.platform.ai.compiler.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.ElementContract;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract.TopologyContract;
import org.yaml.snakeyaml.LoaderOptions;

/** Loads the repository-owned compiler contract YAML from the classpath. Unknown versions fail. */
@ApplicationScoped
public class ClasspathCompilerContractRepository implements CompilerContractRepository {

  static final String V1_RESOURCE = "compiler-contract/create-chain-compiler-contract-v1.yaml";

  private static final List<String> REQUIRED_ELEMENT_TYPES =
      List.of(
          "http-trigger",
          "kafka-trigger-2",
          "async-api-trigger",
          "service-call",
          "script",
          "mapper-2",
          "condition",
          "split-2",
          "split-async-2",
          "loop-2",
          "try-catch-finally-2");

  private static final ObjectMapper YAML_MAPPER = strictYamlMapper();

  private final Map<String, CompilerContract> contracts;

  public ClasspathCompilerContractRepository() {
    this(ClasspathCompilerContractRepository.class.getClassLoader());
  }

  ClasspathCompilerContractRepository(ClassLoader classLoader) {
    CompilerContract v1 = load(classLoader, V1_RESOURCE);
    if (!CompilerContract.V1.equals(v1.contractVersion())) {
      throw new IllegalStateException(
          "Compiler contract resource "
              + V1_RESOURCE
              + " declares version "
              + v1.contractVersion());
    }
    this.contracts = Map.of(CompilerContract.V1, v1);
  }

  @Override
  public CompilerContract require(String contractVersion) {
    CompilerContract contract = contracts.get(contractVersion);
    if (contract == null) {
      throw new IllegalStateException("Unsupported compiler contract version: " + contractVersion);
    }
    return contract;
  }

  static CompilerContract parse(String yaml) {
    Objects.requireNonNull(yaml, "yaml");
    ParsedContract parsed;
    try {
      parsed = YAML_MAPPER.readValue(yaml, ParsedContract.class);
    } catch (Exception e) {
      if (isDuplicateKey(e)) {
        throw new IllegalStateException("Compiler contract YAML contains a duplicate key", e);
      }
      throw new IllegalStateException(
          "Failed to parse compiler contract YAML: " + e.getMessage(), e);
    }
    Set<String> requiredArtifacts = copyRequiredArtifacts(parsed.requiredArtifacts());
    return new CompilerContract(
        parsed.contractVersion(),
        parsed.semanticSchemaVersion(),
        parsed.elements(),
        parsed.topology(),
        requiredArtifacts,
        copyIdentifiers(parsed.requiredAddons()),
        copyIdentifiers(parsed.requiredKnowledgeFragments()),
        sha256(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  private static CompilerContract load(ClassLoader classLoader, String resource) {
    try (InputStream stream = classLoader.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Compiler contract resource is missing: " + resource);
      }
      String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      CompilerContract contract = parse(yaml);
      requireElementMappings(contract.elements());
      return contract;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load compiler contract resource: " + resource, e);
    }
  }

  private static void requireElementMappings(Map<String, ElementContract> elements) {
    for (String elementType : REQUIRED_ELEMENT_TYPES) {
      if (!elements.containsKey(elementType)) {
        throw new IllegalStateException(
            "Compiler contract is missing required element mapping: " + elementType);
      }
    }
  }

  private static Set<String> copyRequiredArtifacts(List<String> identifiers) {
    if (identifiers == null) {
      return Set.of();
    }
    LinkedHashSet<String> copied = new LinkedHashSet<>();
    for (String identifier : identifiers) {
      if (identifier == null || identifier.isBlank()) {
        throw new IllegalStateException("Required artifact identifier must not be empty");
      }
      copied.add(identifier);
    }
    return Set.copyOf(copied);
  }

  private static Set<String> copyIdentifiers(List<String> identifiers) {
    if (identifiers == null) {
      return Set.of();
    }
    return Set.copyOf(new LinkedHashSet<>(identifiers));
  }

  private static boolean isDuplicateKey(Throwable error) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("duplicate key") || normalized.contains("duplicate field")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  private static String sha256(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static ObjectMapper strictYamlMapper() {
    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setAllowDuplicateKeys(false);
    ObjectMapper mapper =
        new ObjectMapper(YAMLFactory.builder().loaderOptions(loaderOptions).build());
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    return mapper;
  }

  private record ParsedContract(
      String contractVersion,
      String semanticSchemaVersion,
      Map<String, ElementContract> elements,
      Map<String, TopologyContract> topology,
      List<String> requiredArtifacts,
      List<String> requiredAddons,
      List<String> requiredKnowledgeFragments) {}
}
