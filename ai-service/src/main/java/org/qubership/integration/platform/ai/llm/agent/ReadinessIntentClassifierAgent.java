package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Classifies which generator intent concepts a build request asks for.
 *
 * <p>Replaces the former keyword matching in {@code GeneratorReadinessEvaluator}. The caller passes
 * the intent catalog (concept id + description per line); the agent returns the matching concept ids
 * as a comma-separated list, or an empty reply when none apply.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ReadinessIntentClassifierAgent {

  @SystemMessage(fromResource = "prompts/roles/readiness-intent-classifier.md")
  @UserMessage(
      """
Intent catalog (concept id: what the user is asking for):
{intentCatalog}

User request:
{userRequest}

Requirement brief (may be empty):
{requirementBrief}

Reply with ONLY the concept ids that the request asks for, comma-separated, no prose. Reply with an empty line if none apply.\
""")
  String classify(String intentCatalog, String userRequest, String requirementBrief);
}
