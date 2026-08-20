package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.llm.agent.PlanningKickoffAgent;
import org.qubership.integration.platform.ai.productpipeline.artifact.IdsBypass;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Product-pipeline planning stage. Adapts {@link CompilerPlanningRunner} and stops at verified plan
 * candidates — never creates a generated-chain bundle or calls publication.
 */
@ApplicationScoped
public class PlanningCapability implements StageCapability {

  private static final Logger LOG = Logger.getLogger(PlanningCapability.class);

  public static final String CAPABILITY_ID = "planning";

  private static final String FALLBACK_KICKOFF =
      "Creating the implementation plan and starting generators.";

  private final CompilerPlanningRunner planningRunner;
  private final CompilerDerivedPlanningRunner derivedPlanningRunner;
  private final PlanningKickoffAgent kickoffAgent;

  @Inject
  public PlanningCapability(
      CompilerPlanningRunner planningRunner,
      CompilerDerivedPlanningRunner derivedPlanningRunner,
      PlanningKickoffAgent kickoffAgent) {
    this.planningRunner = Objects.requireNonNull(planningRunner, "planningRunner");
    this.derivedPlanningRunner =
        Objects.requireNonNull(derivedPlanningRunner, "derivedPlanningRunner");
    this.kickoffAgent = kickoffAgent;
  }

  /** Test helper without kickoff LLM. */
  PlanningCapability(
      CompilerPlanningRunner planningRunner, CompilerDerivedPlanningRunner derivedPlanningRunner) {
    this(planningRunner, derivedPlanningRunner, null);
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    RequirementBrief brief = resolveBrief(context);
    if (brief == null) {
      return Multi.createFrom()
          .item(
              new CapabilitySignal.Completed(
                  StageOutcome.of(
                      StageOutcomeClass.MISSING_MANDATORY_INPUT,
                      "Requirement brief is required for planning")));
    }
    IdsBypass bypass = resolveIdsBypass(context);
    CompilerPlanningRequest request =
        new CompilerPlanningRequest(
            context.conversationId(),
            context.runId(),
            brief,
            bypass,
            context.attributeAsString("languageVersion"),
            ListCopy.dependencyClosure(context),
            ListCopy.expectedSkills(context),
            context.attemptId());
    if (context.profile() != null && context.profile().compilerPipeline() != null) {
      return Multi.createBy()
          .concatenating()
          .streams(
              kickoffAnnouncement(
                  brief,
                  context.runManifest() == null ? "en" : context.runManifest().responseLocale()),
              derivedPlanningRunner.planWithProgress(request))
          .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
    return planningRunner.plan(request);
  }

  private Multi<CapabilitySignal> kickoffAnnouncement(RequirementBrief brief, String responseLocale) {
    return Uni.createFrom()
        .item(
            () ->
                (CapabilitySignal)
                    new CapabilitySignal.Message(resolveKickoffText(brief, responseLocale)))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
        .toMulti();
  }

  private String resolveKickoffText(RequirementBrief brief, String responseLocale) {
    String text = FALLBACK_KICKOFF;
    if (kickoffAgent != null) {
      String reference = kickoffLanguageReference(brief);
      try {
        String announced = kickoffAgent.announce(normalizedLocale(responseLocale), reference);
        if (announced != null && !announced.isBlank()) {
          text = announced.trim();
        }
      } catch (RuntimeException ex) {
        LOG.warnf(ex, "Planning kickoff announcement failed; using fallback English sentence");
      }
    }
    return text;
  }

  static String kickoffLanguageReference(RequirementBrief brief) {
    if (brief == null) {
      return "Create an integration chain.";
    }
    if (brief.summary() != null && !brief.summary().isBlank()) {
      return brief.summary().trim();
    }
    if (brief.goal() != null && !brief.goal().isBlank()) {
      return brief.goal().trim();
    }
    if (brief.approvedDraftText() != null && !brief.approvedDraftText().isBlank()) {
      return brief.approvedDraftText().trim();
    }
    return "Create an integration chain.";
  }

  private static String normalizedLocale(String responseLocale) {
    return responseLocale == null || responseLocale.isBlank() ? "en" : responseLocale.trim();
  }

  /**
   * Live runtime hydrates {@code requirementBrief} but not {@code idsBypass}. When the active
   * profile declares {@code compilerPipeline}, default from that profile so {@link
   * CompilerDerivedPlanningSpine} does not treat the request as legacy planning profile.
   */
  private static IdsBypass resolveIdsBypass(StageExecutionContext context) {
    if (context.attributes().get("idsBypass") instanceof IdsBypass ids) {
      return ids;
    }
    if (context.profile() != null && context.profile().compilerPipeline() != null) {
      return new IdsBypass(
          "profile-bypass", context.profile().profileId(), context.profile().profileVersion());
    }
    return null;
  }

  private static RequirementBrief resolveBrief(StageExecutionContext context) {
    Object attribute = context.attributes().get("requirementBrief");
    if (attribute instanceof RequirementBrief brief) {
      return brief;
    }
    return null;
  }

  private static final class ListCopy {
    private static java.util.List<String> dependencyClosure(StageExecutionContext context) {
      Object value = context.attributes().get("dependencyClosure");
      if (value instanceof java.util.List<?> list) {
        return list.stream().map(Object::toString).toList();
      }
      return java.util.List.of();
    }

    private static java.util.List<String> expectedSkills(StageExecutionContext context) {
      Object value = context.attributes().get("expectedSkillOrder");
      if (value instanceof java.util.List<?> list) {
        return list.stream().map(Object::toString).toList();
      }
      return java.util.List.of();
    }
  }
}
