package org.qubership.integration.platform.ai.productpipeline.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fails closed while any Java JSONL knowledge backend source, fixture, package, property, or
 * import remains in the tree.
 */
class SingleKnowledgeBackendArchitectureTest {

  private static final Path MODULE_ROOT = resolveModuleRoot();
  private static final Path REPO_ROOT = MODULE_ROOT.getParent();
  private static final Path AI_SERVICE = MODULE_ROOT;
  private static final Path SKILLS = REPO_ROOT.resolve("integration-platform-skills");

  private static final List<String> FORBIDDEN_LEGACY_SYMBOLS =
      List.of(
          "QipKnowledgeTools",
          "CompilerSkillKnowledgeBudget",
          "KnowledgeAttachmentAssembler",
          "KnowledgeNeedResolver",
          "NeedNormalizer",
          "KnowledgeMapRenderer",
          "DeliveryPolicy",
          "QipKnowledgeRefExtractor",
          "StructuredQipKnowledgeRefs",
          "QipKnowledgeLookupService",
          "KnowledgeSourceResolver",
          "KnowledgeSourceContract",
          "NativeKnowledgeBuildSpec",
          "NativeKnowledgeProfileSpec",
          "native-knowledge-build-spec.json",
          "getGeneratorContract",
          "getValidationRule",
          "listGeneratorContracts",
          "searchCompilerKnowledge");

  private static Path resolveModuleRoot() {
    Path cwd = Path.of(".").toAbsolutePath().normalize();
    if (Files.isRegularFile(cwd.resolve("pom.xml"))
        && Files.isDirectory(cwd.resolve("src/main/java"))) {
      return cwd;
    }
    Path nested = cwd.resolve("ai-service");
    if (Files.isRegularFile(nested.resolve("pom.xml"))) {
      return nested;
    }
    throw new IllegalStateException("Unable to resolve ai-service module root from " + cwd);
  }

  @Test
  void oldJsonlBackendArtifactsAreGone() {
    assertFalse(
        Files.isDirectory(
            AI_SERVICE.resolve(
                "src/main/java/org/qubership/integration/platform/ai/knowledge/runtime")));
    assertFalse(
        Files.isDirectory(
            AI_SERVICE.resolve(
                "src/test/java/org/qubership/integration/platform/ai/knowledge/runtime")));
    assertFalse(Files.isDirectory(AI_SERVICE.resolve("src/test/resources/knowledge-runtime-fixture")));
    assertFalse(Files.isDirectory(SKILLS.resolve("knowledge-package/default")));
    assertTrue(Files.isDirectory(SKILLS.resolve("knowledge/default")));
    assertTrue(
        Files.isRegularFile(
            SKILLS.resolve("knowledge/default/ai/GENERATOR_CONTRACTS.md")));
    assertFalse(Files.exists(SKILLS.resolve("scripts/sync-knowledge.sh")));
  }

  @Test
  void productionSourcesDoNotReferenceDeletedBackend() throws IOException {
    assertNoMatch(
        AI_SERVICE.resolve("src/main"),
        "KnowledgePackageLoader|KnowledgeObjectStore|KnowledgeRuntime|knowledge\\.runtime|qip\\.knowledge\\.(full|default)-package-dir|knowledge-package/default|knowledge-runtime-fixture/default|knowledge/default");
    assertNoMatch(
        SKILLS.resolve("README.md"),
        "KnowledgePackageLoader|KnowledgeObjectStore|KnowledgeRuntime|qip\\.knowledge\\.(full|default)-package-dir|knowledge-package/default|sync-knowledge\\.sh");
    assertNoMatch(
        SKILLS.resolve("scripts"),
        "KnowledgePackageLoader|KnowledgeObjectStore|KnowledgeRuntime|qip\\.knowledge\\.(full|default)-package-dir|knowledge-package/default|sync-knowledge\\.sh");
    assertNoMatch(
        SKILLS.resolve("product-pipelines"),
        "KnowledgePackageLoader|KnowledgeObjectStore|KnowledgeRuntime|qip\\.knowledge\\.(full|default)-package-dir|knowledge-package/default");
    assertNoMatch(
        REPO_ROOT.resolve("infrastructure"),
        "qip\\.knowledge\\.(full|default)-package-dir|knowledge-package/default|KnowledgeRuntime");
  }

  @Test
  void noJsonlObjectStoresRemainUnderTestOrSkills() throws IOException {
    Path testResources = AI_SERVICE.resolve("src/test/resources");
    if (Files.isDirectory(testResources)) {
      try (Stream<Path> paths = Files.walk(testResources)) {
        assertTrue(
            paths
                .filter(path -> path.getFileName().toString().equals("objects.jsonl"))
                .findAny()
                .isEmpty());
      }
    }
    assertFalse(Files.exists(SKILLS.resolve("knowledge-package/default/objects.jsonl")));
  }

  @Test
  void pomDoesNotCopyDefaultKnowledgePackage() throws IOException {
    String pom = Files.readString(AI_SERVICE.resolve("pom.xml"), StandardCharsets.UTF_8);
    assertFalse(pom.contains("copy-default-knowledge-package-into-classes"));
    assertFalse(pom.contains("knowledge-package/default"));
  }

  @Test
  void applicationPropertiesDropLegacyPackageDirs() throws IOException {
    String props =
        Files.readString(
            AI_SERVICE.resolve("src/main/resources/application.properties"), StandardCharsets.UTF_8);
    assertFalse(props.contains("qip.knowledge.full-package-dir"));
    assertFalse(props.contains("qip.knowledge.default-package-dir"));
  }

  @Test
  void productionSourcesDoNotRetainProseDerivedIdentityOrLlmLookupTools() throws IOException {
    Path productionRoot = AI_SERVICE.resolve("src/main");
    String forbiddenPattern = String.join("|", FORBIDDEN_LEGACY_SYMBOLS);
    assertNoMatch(productionRoot, forbiddenPattern);
    assertNoRegisterAiServiceUsesQipKnowledgeTools(productionRoot);
  }

  private static void assertNoRegisterAiServiceUsesQipKnowledgeTools(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path :
          paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.contains("@RegisterAiService")) {
          continue;
        }
        assertFalse(
            text.contains("QipKnowledgeTools.class"),
            () -> "@RegisterAiService must not list QipKnowledgeTools.class in " + path);
      }
    }
  }

  private static void assertNoMatch(Path root, String regex) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    if (Files.isRegularFile(root)) {
      assertFileDoesNotMatch(root, regex);
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        assertFileDoesNotMatch(path, regex);
      }
    }
  }

  private static void assertFileDoesNotMatch(Path path, String regex) throws IOException {
    String name = path.getFileName().toString();
    if (!(name.endsWith(".java")
        || name.endsWith(".properties")
        || name.endsWith(".xml")
        || name.endsWith(".md")
        || name.endsWith(".yml")
        || name.endsWith(".yaml")
        || name.endsWith(".sh"))) {
      return;
    }
    String text = Files.readString(path, StandardCharsets.UTF_8);
    assertFalse(
        java.util.regex.Pattern.compile(regex).matcher(text).find(),
        () -> "Forbidden knowledge-backend residue in " + path);
  }
}
