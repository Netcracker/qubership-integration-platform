package org.qubership.integration.platform.ai.compiler.runtimepkg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackFileKind;
import org.qubership.integration.platform.ai.qipknowledge.pack.CipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.ScannedCipKnowledgeFile;

/** Builds a thin index over selected compiler-runtime-package YAML artifacts. */
public class CompilerRuntimePackageIndexLoader {

  private static final Map<String, String> REQUIRED_ARTIFACTS =
      Map.ofEntries(
          Map.entry("compiler-runtime-package/language-model.yaml", "language-model"),
          Map.entry("compiler-runtime-package/grammar-model.yaml", "grammar-model"),
          Map.entry("compiler-runtime-package/semantic-model.yaml", "semantic-model"),
          Map.entry("compiler-runtime-package/rule-engine.yaml", "rule-engine"),
          Map.entry("compiler-runtime-package/decision-tree.yaml", "decision-tree"),
          Map.entry("compiler-runtime-package/generator-packages.yaml", "generator-packages"),
          Map.entry("compiler-runtime-package/validation-rules.yaml", "validation-rules"),
          Map.entry("compiler-runtime-package/runtime-capabilities.yaml", "runtime-capabilities"));

  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  public CompilerRuntimePackageIndex load(CipKnowledgePackScanResult scanResult) {
    List<CompilerRuntimePackageArtifact> artifacts = new ArrayList<>();
    for (ScannedCipKnowledgeFile file : scanResult.files()) {
      if (file.kind() != CipKnowledgePackFileKind.RUNTIME_PACKAGE_ARTIFACT) {
        continue;
      }
      String artifactType = REQUIRED_ARTIFACTS.get(file.relativePath());
      if (artifactType == null) {
        continue;
      }
      artifacts.add(
          new CompilerRuntimePackageArtifact(
              file.relativePath(), artifactType, file.sha256(), topLevelKeys(file)));
    }
    artifacts.sort(Comparator.comparing(CompilerRuntimePackageArtifact::path));
    return new CompilerRuntimePackageIndex(artifacts);
  }

  private List<String> topLevelKeys(ScannedCipKnowledgeFile file) {
    try {
      JsonNode root = yamlMapper.readTree(file.content());
      if (root == null || !root.isObject()) {
        return List.of();
      }
      List<String> keys = new ArrayList<>();
      root.fieldNames().forEachRemaining(keys::add);
      keys.sort(String::compareTo);
      return List.copyOf(keys);
    } catch (IOException e) {
      return List.of();
    }
  }
}
