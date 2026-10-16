package org.qubership.integration.platform.ai.compiler.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.CaptureRoute;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;

/** ai-service pipeline skills that are not part of the CIP compiler APM skill pack. */
public final class InternalPipelineSkills {

  public static final String PLAN_VALIDATOR = "plan-validator";

  private static final String PLAN_VALIDATOR_SKILL_RESOURCE =
      "prompts/internal/plan-validator-skill.md";

  private static final Set<String> SKILL_IDS = Set.of(PLAN_VALIDATOR);

  private InternalPipelineSkills() {}

  public static boolean isInternal(String skillId) {
    return SKILL_IDS.contains(normalize(skillId));
  }

  public static Optional<CompilerSkillDocument> document(String skillId) {
    String normalized = normalize(skillId);
    if (!PLAN_VALIDATOR.equals(normalized)) {
      return Optional.empty();
    }
    return Optional.of(
        new CompilerSkillDocument(
            PLAN_VALIDATOR,
            PLAN_VALIDATOR,
            PLAN_VALIDATOR_SKILL_RESOURCE,
            PLAN_VALIDATOR,
            QipKnowledgeCapabilityPhase.VALIDATOR,
            true,
            QipKnowledgePackVersion.fromPath(java.nio.file.Path.of("internal")),
            loadResource(PLAN_VALIDATOR_SKILL_RESOURCE)));
  }

  public static Optional<CaptureRoute> captureRoute(String skillId) {
    if (!PLAN_VALIDATOR.equals(normalize(skillId))) {
      return Optional.empty();
    }
    return Optional.of(new CaptureRoute(PLAN_VALIDATOR, CaptureTool.CAPTURE_VALIDATION_RESULT));
  }

  private static String normalize(String skillId) {
    Objects.requireNonNull(skillId, "skillId");
    return skillId.trim().toLowerCase(Locale.ROOT);
  }

  private static String loadResource(String resourcePath) {
    try (InputStream stream =
        InternalPipelineSkills.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (stream == null) {
        throw new IllegalStateException("Missing internal pipeline skill resource: " + resourcePath);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read internal pipeline skill: " + resourcePath, e);
    }
  }
}
