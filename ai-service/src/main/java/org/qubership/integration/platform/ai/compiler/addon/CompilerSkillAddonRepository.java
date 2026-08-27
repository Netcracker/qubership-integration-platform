package org.qubership.integration.platform.ai.compiler.addon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

/** Loads compiler skill addon documents from pre-built classpath or filesystem indexes. */
@ApplicationScoped
public class CompilerSkillAddonRepository {

  private static final String CLASSPATH_ROOT = "qipknowledge/";

  private final QipKnowledgePackVersion activeVersion;
  private final Path filesystemBaseDir;
  private final ClassLoader classLoader;
  private final ObjectMapper objectMapper;

  @Inject
  public CompilerSkillAddonRepository(QipKnowledgePackRepository repository) {
    this(repository.activeVersion(), null, CompilerSkillAddonRepository.class.getClassLoader());
  }

  /** Test-only filesystem-backed repository over a generated qipknowledge output root. */
  public static CompilerSkillAddonRepository forFilesystem(
      Path filesystemBaseDir,
      QipKnowledgePackVersion activeVersion,
      ClassLoader classLoader) {
    return new CompilerSkillAddonRepository(activeVersion, filesystemBaseDir, classLoader);
  }

  CompilerSkillAddonRepository(
      QipKnowledgePackVersion activeVersion, Path filesystemBaseDir, ClassLoader classLoader) {
    this.activeVersion = Objects.requireNonNull(activeVersion, "activeVersion");
    this.filesystemBaseDir = filesystemBaseDir;
    this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  }

  public CompilerSkillAddonContext loadForSkill(String capabilityId) {
    String skillId = requireSkillId(capabilityId);
    CompilerSkillAddonIndex index = loadIndex();

    List<CompilerSkillAddonDocument> globalDocuments = new ArrayList<>();
    for (String relativePath : index.globalDocuments()) {
      readDocument(relativePath).ifPresent(globalDocuments::add);
    }

    CompilerSkillAddonIndex.CompilerSkillAddonSkillIndex skillIndex = index.skills().get(skillId);
    if (skillIndex == null) {
      return new CompilerSkillAddonContext(
          List.copyOf(globalDocuments), null, List.of());
    }

    CompilerSkillAddonDocument skillAddon =
        readDocument(skillIndex.addonDocument()).orElse(null);
    List<CompilerSkillAddonDocument> examples = new ArrayList<>();
    for (String examplePath : skillIndex.examples()) {
      readDocument(examplePath).ifPresent(examples::add);
    }

    return new CompilerSkillAddonContext(
        List.copyOf(globalDocuments), skillAddon, List.copyOf(examples));
  }

  public Optional<AddonRuntimeMetadata> loadRuntimeMetadata(String capabilityId) {
    String skillId = requireSkillId(capabilityId);
    CompilerSkillAddonIndex index = loadIndex();
    CompilerSkillAddonIndex.CompilerSkillAddonSkillIndex skillIndex = index.skills().get(skillId);
    if (skillIndex == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(skillIndex.runtimeMetadata());
  }

  /** Reads a global data document such as {@code global/materialization-requirements.yaml}. */
  public Optional<String> readGlobalDataDocument(String relativePath) {
    return readTextDocument(relativePath);
  }

  private CompilerSkillAddonIndex loadIndex() {
    try {
      if (filesystemBaseDir != null) {
        Path indexFile = addonsDir().resolve(CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE);
        if (!Files.isRegularFile(indexFile)) {
          throw missingAddonIndex();
        }
        return objectMapper.readValue(Files.readString(indexFile), CompilerSkillAddonIndex.class);
      }

      String resourcePath = classpathAddonsRoot() + CompilerSkillAddonBuildSupport.ADDON_INDEX_FILE;
      try (InputStream stream = openClasspathResource(resourcePath)) {
        if (stream == null) {
          throw missingAddonIndex();
        }
        return objectMapper.readValue(stream, CompilerSkillAddonIndex.class);
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to load compiler skill addon index for version " + activeVersion.normalized(),
          e);
    }
  }

  private IllegalStateException missingAddonIndex() {
    return new IllegalStateException(
        "Compiler skill addon index is missing for version " + activeVersion.normalized());
  }

  private Optional<CompilerSkillAddonDocument> readDocument(String relativePath) {
    return readTextDocument(relativePath)
        .map(content -> new CompilerSkillAddonDocument(relativePath, content));
  }

  private Optional<String> readTextDocument(String relativePath) {
    try {
      if (filesystemBaseDir != null) {
        Path file = addonsDir().resolve(relativePath);
        if (!Files.isRegularFile(file)) {
          return Optional.empty();
        }
        return Optional.of(CompilerSkillAddonBuildSupport.readText(file));
      }

      String resourcePath = classpathAddonsRoot() + relativePath;
      try (InputStream stream = openClasspathResource(resourcePath)) {
        if (stream == null) {
          return Optional.empty();
        }
        String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return Optional.of(content);
      }
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private Path addonsDir() {
    return filesystemBaseDir
        .resolve(activeVersion.normalized())
        .resolve(CompilerSkillAddonBuildSupport.ADDONS_DIR);
  }

  private String classpathAddonsRoot() {
    return CLASSPATH_ROOT
        + activeVersion.normalized()
        + "/"
        + CompilerSkillAddonBuildSupport.ADDONS_DIR
        + "/";
  }

  private InputStream openClasspathResource(String resourcePath) {
    return classLoader.getResourceAsStream(resourcePath);
  }

  private static String requireSkillId(String capabilityId) {
    Objects.requireNonNull(capabilityId, "capabilityId");
    String trimmed = capabilityId.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("capabilityId is required");
    }
    return trimmed;
  }
}
