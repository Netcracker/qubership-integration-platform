package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocument;
import org.qubership.integration.platform.ai.compiler.CompilerSkillDocumentService;
import org.qubership.integration.platform.ai.compiler.addon.AddonPromptMaterialStripper;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.llm.agent.DesignPlanCaptureAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Production runner for the typed design-plan capture boundary. */
@ApplicationScoped
public class DefaultDesignPlanSkillRunner implements DesignPlanSkillRunner {

  private final CompilerSkillDocumentService documentService;
  private final CompilerSkillAddonRepository addonRepository;
  private final DesignPlanCaptureAgent agent;

  @Inject
  public DefaultDesignPlanSkillRunner(
      CompilerSkillDocumentService documentService,
      CompilerSkillAddonRepository addonRepository,
      DesignPlanCaptureAgent agent) {
    this.documentService = Objects.requireNonNull(documentService, "documentService");
    this.addonRepository = Objects.requireNonNull(addonRepository, "addonRepository");
    this.agent = Objects.requireNonNull(agent, "agent");
  }

  @Override
  public Result runOnce(
      String conversationId,
      String input,
      Optional<String> formatFailure,
      Optional<String> repairEvidence,
      String pinnedSkillHash,
      String apiRelease,
      ChainSemanticRevision revision,
      RequirementBrief brief,
      CompilerRunPin pin) {
    CompilerSkillDocument document = documentService.loadByCapabilityId(CipDesignPlannerAdapter.SKILL_ID);
    String actualHash = sha256(document.markdown());
    if (!pinnedSkillHash.equals(actualHash)) {
      throw new PlannerContractException(
          "pinned skill hash mismatch for "
              + CipDesignPlannerAdapter.SKILL_ID
              + ": expected "
              + pinnedSkillHash
              + " but was "
              + actualHash);
    }
    CompilerSkillAddonContext addon = addonRepository.loadForSkill(CipDesignPlannerAdapter.SKILL_ID);
    String prompt = buildPrompt(document, addon, input, formatFailure, repairEvidence);
    DesignPlanCaptureSession.Binding binding =
        DesignPlanCaptureSession.bind(
            conversationId, revision, pin.subjectSha256(), apiRelease, brief, pin);
    ToolSession.bind(conversationId);
    String raw = "";
    try {
      var response =
          agent.chat(
              conversationId,
              QuteUserMessageEscaping.escapeForAiServiceUserMessage(prompt));
      raw = response == null || response.content() == null ? "" : response.content().trim();
      return new Result(
          binding.candidate().get(),
          raw,
          binding.rejection().get(),
          binding.rejectionFindings().get(),
          binding.terminal().get());
    } finally {
      DesignPlanCaptureSession.unbind(conversationId);
      ToolSession.clear();
    }
  }

  static String buildPrompt(
      CompilerSkillDocument document,
      CompilerSkillAddonContext addon,
      String input,
      Optional<String> formatFailure,
      Optional<String> repairEvidence) {
    StringBuilder body = new StringBuilder();
    body.append("## Skill\n\n").append(document.markdown().trim());
    if (addon != null && addon.skillAddon() != null) {
      String material = AddonPromptMaterialStripper.stripForPrompt(addon.skillAddon().content());
      if (!material.isBlank()) {
        body.append("\n\n## Runtime addon\n\n").append(material.trim());
      }
    }
    body.append("\n\n## Typed planning contract\n\n");
    body.append("Call captureDesignPlan with notes for exact approved targets, or notes=[] if ");
    body.append("none need a custom description. The tool contract overrides Markdown output ");
    body.append("instructions above. The server derives owners, target claims, dependencies, ");
    body.append("structure, assembly, and validation steps.\n\n## Design input\n\n");
    body.append(input.trim());
    repairEvidence.filter(value -> !value.isBlank())
        .ifPresent(value -> body.append("\n\n## Repair evidence\n\n").append(value.trim()));
    formatFailure.filter(value -> !value.isBlank())
        .ifPresent(value -> body.append("\n\n## Capture findings\n\n").append(value.trim()));
    return body.toString();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
