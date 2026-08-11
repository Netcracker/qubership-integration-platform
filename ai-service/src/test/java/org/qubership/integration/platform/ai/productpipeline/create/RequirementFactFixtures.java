package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;

/** Shared Greetings / LangRouter fact fixtures for Task 10 coverage tests. */
public final class RequirementFactFixtures {

  public static final String GREETINGS_PROMPT =
      "Create chain named \"Greetings\", it receives GET call on internal route \"/greetings\""
          + " and returns \"Hello world!\". Return plain text directly from a script."
          + " No service calls. No error handling. No MCP. No chain failure handler."
          + " No file operations. No SFTP. No SDS. No context storage. No messaging."
          + " No JMS. No Pub/Sub. No XSLT. No ABAC.";

  public static final String LANG_ROUTER_PROMPT =
      "Create chain named \"LangRouter\". HTTP GET internal \"/lang-router\" with query"
          + " parameter preferredLang. Flow: http-trigger then condition/if/else routing."
          + " If preferredLang is ru: script returns Russian greeting text. Else branch:"
          + " script returns English greeting text. No service calls. No catalog binding."
          + " No API Hub import. No error handling wrapper. No RBAC. No MCP.";

  private RequirementFactFixtures() {}

  public static List<RequirementFact> greetingsFacts() {
    List<RequirementFact> facts = new ArrayList<>();
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.GOAL,
            "chain",
            "Create chain named \"Greetings\""));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "GET /greetings"));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.VISIBILITY,
            "http-trigger",
            "internal route"));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "script",
            "Return plain text directly from a script"));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "script",
            "returns \"Hello world!\""));
    addNegative(facts, "service-call", "No service calls");
    addNegative(facts, "error-handling", "No error handling");
    addNegative(facts, "mcp", "No MCP");
    addNegative(facts, "chain-failure-handler", "No chain failure handler");
    addNegative(facts, "file-operations", "No file operations");
    addNegative(facts, "sftp", "No SFTP");
    addNegative(facts, "sds", "No SDS");
    addNegative(facts, "context-storage", "No context storage");
    addNegative(facts, "messaging", "No messaging");
    addNegative(facts, "jms", "No JMS");
    addNegative(facts, "pubsub", "No Pub/Sub");
    addNegative(facts, "xslt", "No XSLT");
    addNegative(facts, "abac", "No ABAC");
    return List.copyOf(facts);
  }

  public static List<RequirementFact> greetingsNegativeFacts() {
    return greetingsFacts().stream()
        .filter(fact -> fact.polarity() == RequirementFactPolarity.NEGATIVE)
        .toList();
  }

  public static List<RequirementFact> langRouterFacts() {
    List<RequirementFact> facts = new ArrayList<>();
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.GOAL,
            "chain",
            "Create chain named \"LangRouter\""));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "GET /lang-router"));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.VISIBILITY,
            "http-trigger",
            "internal"));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.PARAMETER,
            "http-trigger",
            "preferredLang"));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ROUTING,
            "condition",
            "If preferredLang is ru: script returns Russian greeting text"));
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ROUTING,
            "else",
            "Else branch: script returns English greeting text"));
    addNegative(facts, "service-call", "No service calls");
    addNegative(facts, "catalog", "No catalog binding");
    addNegative(facts, "apihub", "No API Hub import");
    addNegative(facts, "error-handling", "No error handling wrapper");
    addNegative(facts, "rbac", "No RBAC");
    addNegative(facts, "mcp", "No MCP");
    return List.copyOf(facts);
  }

  public static RequirementDraft greetingsApprovedDraft() {
    return new RequirementDraft(
        true,
        GREETINGS_PROMPT,
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        null,
        false,
        greetingsFacts());
  }

  public static RequirementDraft langRouterApprovedDraft() {
    return new RequirementDraft(
        true,
        LANG_ROUTER_PROMPT,
        DraftDecision.READY_FOR_PLAN,
        List.of(),
        "brainstorming",
        "1",
        null,
        null,
        null,
        false,
        langRouterFacts());
  }

  private static void addNegative(List<RequirementFact> facts, String key, String text) {
    facts.add(
        RequirementFact.of(
            RequirementFactPolarity.NEGATIVE, RequirementFactKind.CONSTRAINT, key, text));
  }
}
