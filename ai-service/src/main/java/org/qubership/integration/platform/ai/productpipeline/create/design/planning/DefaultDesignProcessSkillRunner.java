package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.AddonPromptMaterialStripper;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.llm.agent.DesignProcessSkillAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;

/**
 * Loads a pinned immutable process skill, verifies its content hash, and invokes the no-tools
 * agent once.
 */
@ApplicationScoped
public class DefaultDesignProcessSkillRunner implements DesignProcessSkillRunner {

  private final CompilerSkillDocumentService documentService;
  private final CompilerSkillAddonRepository addonRepository;
  private final DesignProcessSkillAgent agent;

  @Inject
  public DefaultDesignProcessSkillRunner(
      CompilerSkillDocumentService documentService,
      CompilerSkillAddonRepository addonRepository,
      DesignProcessSkillAgent agent) {
    this.documentService = documentService;
    this.addonRepository = addonRepository;
    this.agent = agent;
  }

  @Override
  public String runOnce(
      String conversationId,
      String skillId,
      String input,
      Optional<String> formatFailure,
      String pinnedSkillHash) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(skillId, "skillId");
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(formatFailure, "formatFailure");
    Objects.requireNonNull(pinnedSkillHash, "pinnedSkillHash");

    CompilerSkillDocument document = documentService.loadByCapabilityId(skillId);
    String actual = sha256(document.markdown());
    if (!pinnedSkillHash.equals(actual)) {
      throw new PlannerContractException(
          "pinned skill hash mismatch for "
              + skillId
              + ": expected "
              + pinnedSkillHash
              + " but was "
              + actual);
    }

    // Both the skill body and the design input are prose. A brace in either — a mermaid arrow, a
    // JSON example — is content, and reaches the prompt renderer as an expression unless escaped.
    String userMessage =
        QuteUserMessageEscaping.escapeForAiServiceUserMessage(
            buildUserMessage(
                document, addonRepository.loadForSkill(skillId), input, formatFailure));
    List<String> tokens =
        agent.chat(conversationId, userMessage).collect().asList().await().indefinitely();
    StringBuilder rendered = new StringBuilder();
    for (String token : tokens) {
      rendered.append(token == null ? "" : token);
    }
    return rendered.toString().trim();
  }

  static String buildUserMessage(
      CompilerSkillDocument document,
      CompilerSkillAddonContext addon,
      String input,
      Optional<String> formatFailure) {
    StringBuilder body = new StringBuilder();
    body.append("## Skill\n\n");
    body.append(document.markdown() == null ? "" : document.markdown().trim());
    if (addon != null && addon.skillAddon() != null) {
      String promptMaterial =
          AddonPromptMaterialStripper.stripForPrompt(addon.skillAddon().content());
      if (!promptMaterial.isBlank()) {
        body.append("\n\n## Runtime addon\n\n");
        body.append(promptMaterial);
        body.append("\n\nThe runtime addon overrides conflicting workflow instructions above.");
      }
    }
    body.append("\n\n## Design input\n\n");
    body.append(input.trim());
    if (formatFailure.isPresent()) {
      body.append("\n\n## Format failure from previous attempt\n\n");
      body.append(formatFailure.get().trim());
      body.append("\n\nReturn only a corrected plan that satisfies the skill output contract.");
    }
    return body.toString();
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
