package org.qubership.integration.platform.ai.compiler.addon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDescriptor;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillDisposition;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackManifest;
import org.qubership.integration.platform.ai.qipknowledge.pack.UnsupportedQipKnowledgeItem;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityDescriptor;
import org.qubership.integration.platform.ai.qipknowledge.skill.CapabilityRegistry;

/** Applies ai-service addon runtime metadata to ingested compiler catalog artifacts. */
public final class CompilerSkillCatalogAddonOverlay {

  private final AddonRuntimeMetadataParser metadataParser = new AddonRuntimeMetadataParser();

  public OverlayResult apply(
      CompilerSkillCatalog catalog,
      CapabilityRegistry registry,
      QipKnowledgePackManifest manifest,
      List<UnsupportedQipKnowledgeItem> unsupportedItems,
      Path addonPackRoot) {
    Map<String, AddonRuntimeMetadata> promotions = loadPromotions(addonPackRoot);
    if (promotions.isEmpty()) {
      return new OverlayResult(catalog, registry, manifest, unsupportedItems, List.of());
    }

    List<CompilerSkillDescriptor> skills = new ArrayList<>();
    for (CompilerSkillDescriptor skill : catalog.skills()) {
      AddonRuntimeMetadata metadata = promotions.get(skill.name());
      skills.add(metadata != null ? promote(skill, metadata) : skill);
    }
    CompilerSkillCatalog promotedCatalog = new CompilerSkillCatalog(List.copyOf(skills));

    List<CapabilityDescriptor> capabilities = new ArrayList<>();
    for (CapabilityDescriptor capability : registry.capabilities()) {
      if (promotions.containsKey(capability.id())) {
        capabilities.add(
            new CapabilityDescriptor(
                capability.id(),
                capability.sourceSkillId(),
                capability.packVersion(),
                capability.phase(),
                true,
                null,
                                capability.requiredTools(),
                capability.executionOrderHints()));
      } else {
        capabilities.add(capability);
      }
    }
    CapabilityRegistry promotedRegistry =
        new CapabilityRegistry(registry.version(), List.copyOf(capabilities));

    List<String> supportedIds = new ArrayList<>(manifest.supportedCapabilityIds());
    List<String> unsupportedIds = new ArrayList<>(manifest.unsupportedCapabilityIds());
    List<UnsupportedQipKnowledgeItem> remainingUnsupported = new ArrayList<>();
    for (UnsupportedQipKnowledgeItem item : unsupportedItems) {
      if (promotions.containsKey(item.id())) {
        unsupportedIds.remove(item.id());
        if (!supportedIds.contains(item.id())) {
          supportedIds.add(item.id());
        }
        continue;
      }
      remainingUnsupported.add(item);
    }
    supportedIds.sort(String::compareTo);
    unsupportedIds.sort(String::compareTo);

    QipKnowledgePackManifest promotedManifest =
        new QipKnowledgePackManifest(
            manifest.version(),
            manifest.sourcePath(),
            manifest.createdAt(),
            manifest.fileChecksums(),
            manifest.skillIds(),
            List.copyOf(supportedIds),
            List.copyOf(unsupportedIds));

    return new OverlayResult(
        promotedCatalog,
        promotedRegistry,
        promotedManifest,
        List.copyOf(remainingUnsupported),
        List.copyOf(promotions.keySet()));
  }

  private Map<String, AddonRuntimeMetadata> loadPromotions(Path addonPackRoot) {
    if (addonPackRoot == null || !Files.isDirectory(addonPackRoot)) {
      return Map.of();
    }
    Path skillsDir = addonPackRoot.resolve("skills");
    if (!Files.isDirectory(skillsDir)) {
      return Map.of();
    }
    Map<String, AddonRuntimeMetadata> promotions = new LinkedHashMap<>();
    try (var stream = Files.list(skillsDir)) {
      for (Path file : stream.filter(Files::isRegularFile).toList()) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".addon.md")) {
          continue;
        }
        String skillId = fileName.substring(0, fileName.length() - ".addon.md".length());
        AddonRuntimeMetadata metadata = metadataParser.parseAddonFile(file);
        if (metadata != null) {
          promotions.put(skillId, metadata);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to scan addon runtime metadata from " + skillsDir, e);
    }
    return Map.copyOf(promotions);
  }

  private static CompilerSkillDescriptor promote(
      CompilerSkillDescriptor skill, AddonRuntimeMetadata metadata) {
    return new CompilerSkillDescriptor(
        skill.name(),
        metadata.category(),
        skill.path(),
        metadata.runtimeSkill(),
        skill.publicApi(),
        skill.privateMarker(),
        CompilerSkillDisposition.PUBLIC_RUNTIME,
        skill.sourcePaths(),
        skill.substrate(),
        skill.consumes(),
        skill.produces(),
        skill.dependsOn());
  }

  public record OverlayResult(
      CompilerSkillCatalog catalog,
      CapabilityRegistry registry,
      QipKnowledgePackManifest manifest,
      List<UnsupportedQipKnowledgeItem> unsupportedItems,
      List<String> runtimePromotedSkillIds) {}
}
