package org.qubership.integration.platform.ai.compiler.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorPolicy;
import org.qubership.integration.platform.ai.llm.agent.ReadinessIntentClassifierAgent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackIngestionService;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackScanResult;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackTestSupport;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexBuilder;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndexSupport;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspace;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class GeneratorPlanManifestBuilderTest {

  private static final int GENERATOR_COUNT = 24;

  private static CompilerGeneratorPolicy policy;
  private static List<String> generationSkillIds;

  /** Test double for the LLM classifier; {@code reply} is the comma-separated concept list. */
  private static final class FakeIntentClassifier implements ReadinessIntentClassifierAgent {
    String reply = "";
    String lastRequirementBrief = "";

    @Override
    public String classify(String intentCatalog, String userRequest, String requirementBrief) {
      lastRequirementBrief = requirementBrief;
      return reply;
    }
  }

  private final FakeIntentClassifier classifier = new FakeIntentClassifier();
  private final GeneratorPlanManifestBuilder builder =
      new GeneratorPlanManifestBuilder(new GeneratorReadinessEvaluator(), classifier);

  @BeforeAll
  static void buildPolicy() throws Exception {
    policy = QipKnowledgePackTestSupport.buildPolicyFromFixture();
    Path packRoot = QipKnowledgePackFixturePaths.packRoot();
    QipKnowledgePackIngestionService ingestionService = new QipKnowledgePackIngestionService();
    var result = ingestionService.ingest(packRoot);
    var scanResult =
        new QipKnowledgePackScanResult(
            packRoot, result.manifest().version(), result.files());
    generationSkillIds =
        CompilerPipelineIndexSupport.generationSkillIds(
            new CompilerPipelineIndexBuilder().build(scanResult, policy));
  }

  private static final String GREETINGS_E2E_PROMPT =
      "Build a greetings chain: HTTP trigger on /hello, then a script that returns a hello"
          + " message. Keep it minimal — no error handling, no security, no routing, no service"
          + " calls, no MCP, no chain failure handler, no file operations, no SFTP, no SDS,"
          + " no context storage, no messaging, no JMS, no Pub/Sub, no XSLT, no ABAC.";

  @Test
  void greetingsE2ePromptSkipsOptionalGenerators() {
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(greetingsE2eGraph(), GREETINGS_E2E_PROMPT, GREETINGS_E2E_PROMPT);

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GENERATOR_COUNT, manifest.plans().size());
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-naming-generator"));
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-trigger-generator"));
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-script-generator"));
    assertFalse(
        manifest.plans().stream()
            .anyMatch(
                plan ->
                    plan.status() == GeneratorPlanStatus.READY
                        && !"cip-naming-generator".equals(plan.skillId())
                        && !"cip-trigger-generator".equals(plan.skillId())
                        && !"cip-script-generator".equals(plan.skillId())),
        "Only naming, trigger, and script configuration should be ready for minimal greetings E2E");
    for (GeneratorPlan plan : manifest.plans()) {
      assertTrue(
          plan.status() == GeneratorPlanStatus.SKIPPED
              || plan.status() == GeneratorPlanStatus.BLOCKED
              || plan.status() == GeneratorPlanStatus.READY,
          () -> "Unexpected status for " + plan.skillId() + ": " + plan.status());
    }
  }

  @Test
  void structuredRequirementBriefReachesIntentClassifier() {
    InMemorySkillWorkspace workspace =
        workspaceWithStructuredBrief(
            greetingsE2eGraph(),
            GREETINGS_E2E_PROMPT,
            new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                "Call customer API",
                List.of("packageId: pkg-1", "operationId: getCustomer"),
                List.of("protocol: REST"),
                List.of(),
                List.of(),
                ""));

    builder.build(policy, generationSkillIds, workspace);

    assertTrue(classifier.lastRequirementBrief.contains("packageId: pkg-1"));
    assertTrue(classifier.lastRequirementBrief.contains("operationId: getCustomer"));
    assertTrue(classifier.lastRequirementBrief.contains("protocol: REST"));
  }

  @Test
  void greetingsSkipsOptionalGenerators() {
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(greetingsGraph(), GREETINGS_E2E_PROMPT, "");

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GENERATOR_COUNT, manifest.plans().size());
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-naming-generator"));
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-trigger-generator"));
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-script-generator"));
    assertFalse(
        manifest.plans().stream()
            .anyMatch(
                plan ->
                    plan.status() == GeneratorPlanStatus.READY
                        && !"cip-naming-generator".equals(plan.skillId())
                        && !"cip-trigger-generator".equals(plan.skillId())
                        && !"cip-script-generator".equals(plan.skillId())),
        "Only naming, trigger, and script configuration should be ready for minimal internal greetings");
    for (GeneratorPlan plan : manifest.plans()) {
      assertTrue(
          plan.status() == GeneratorPlanStatus.SKIPPED
              || plan.status() == GeneratorPlanStatus.BLOCKED
              || plan.status() == GeneratorPlanStatus.READY,
          () -> "Unexpected status for " + plan.skillId() + ": " + plan.status());
    }
  }

  @Test
  void designFirstGreetingsSuppressesNegatedClassifierIntents() {
    classifier.reply = "branching, rbac, abac, credentials";
    String prompt =
        """
        Create chain named "Greetings", it receives GET call on internal route "/greetings"
        and returns "Hello world!". No error handling. No routing. No security. No RBAC.
        No ABAC. Open access without authentication or authorization.""";
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(greetingsGraph(), prompt, prompt);

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-naming-generator"));
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-trigger-generator"));
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-script-generator"));
    assertEquals(GeneratorPlanStatus.SKIPPED, statusFor(manifest, "cip-routing-generator"));
    assertEquals(GeneratorPlanStatus.SKIPPED, statusFor(manifest, "cip-security-generator"));
  }

  @Test
  void designFirstGreetingsSkipsRoutingWhenClassifierOverMatchesBranching() {
    classifier.reply = "branching";
    String prompt =
        "Create chain named \"Greetings\", it receives GET call on internal route"
            + " \"/greetings\" and returns \"Hello world!\". No error handling. No MCP."
            + " No chain failure handler. No file operations. No SFTP. No SDS. No context"
            + " storage. No messaging. No JMS. No Pub/Sub. No XSLT. No ABAC.";
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(greetingsGraph(), prompt, prompt);

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.SKIPPED, statusFor(manifest, "cip-routing-generator"));
  }

  @Test
  void fortuneCompleteRoutingMarksRoutingGeneratorReadyForAudit() {
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(fortuneCompleteRoutingGraph(), fortunePrompt(), fortunePrompt());

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-routing-generator"));
    assertTrue(
        matchedSignals(manifest, "cip-routing-generator").contains("routing_nodes"));
    assertEquals(GeneratorPlanStatus.SKIPPED, statusFor(manifest, "cip-error-handling-generator"));
  }

  @Test
  void fortuneWithEmptyScriptBodiesMarksScriptGeneratorReady() {
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(fortuneCompleteRoutingGraph(), fortunePrompt(), fortunePrompt());

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-script-generator"));
    assertTrue(
        matchedSignals(manifest, "cip-script-generator").contains("script_nodes_missing_body"));
  }

  @Test
  void completeTryCatchDoesNotRouteTopologyRemovalToTheConfigurationOwner() {
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(fortuneWithCompleteTryCatchGraph(), fortunePrompt(), fortunePrompt());

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-routing-generator"));
    assertTrue(
        matchedSignals(manifest, "cip-routing-generator").contains("routing_nodes"));
    assertEquals(GeneratorPlanStatus.SKIPPED, statusFor(manifest, "cip-error-handling-generator"));
  }

  @Test
  void fortuneBranchingIntentWithoutRoutingMarksRoutingReady() {
    classifier.reply = "branching";
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(
            linearFortuneIntentGraph(),
            fortunePrompt(),
            "route by preferredLang with condition/if/else");

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-routing-generator"));
    assertTrue(
        matchedSignals(manifest, "cip-routing-generator")
            .contains("branching_without_routing_nodes"));
  }

  @Test
  void incompleteRoutingGraphMarksRoutingReady() {
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(incompleteRoutingGraph(), fortunePrompt(), fortunePrompt());

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-routing-generator"));
    assertTrue(
        matchedSignals(manifest, "cip-routing-generator").contains("incomplete_routing_nodes"));
  }

  @Test
  void explicitErrorHandlingWithMinimalGraphMarksEhReady() {
    String prompt =
        """
        Create chain named "SafeGreetings".
        HTTP GET internal "/safe-greetings" returns "Hello world!".
        Plan structure only: http-trigger → script. Add error handling via try-catch-finally-2.
        No service calls, no security, no routing.""";
    classifier.reply = "error_handling";
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(greetingsGraph(), prompt, prompt);

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-error-handling-generator"));
    assertTrue(
        matchedSignals(manifest, "cip-error-handling-generator")
            .contains("explicit_error_handling"));
    assertEquals(GeneratorPlanStatus.SKIPPED, statusFor(manifest, "cip-security-generator"));
    assertEquals(GeneratorPlanStatus.SKIPPED, statusFor(manifest, "cip-routing-generator"));
  }

  @Test
  void secureHelloMarksSecurityReady() {
    classifier.reply = "rbac";
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(
            secureHelloGraph(),
            "Secure external hello with RBAC qip-viewer",
            "external route with RBAC");

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-security-generator"));
    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-trigger-generator"));
    assertTrue(matchedSignals(manifest, "cip-security-generator").contains("rbac"));
  }

  @Test
  void serviceCallSkeletonWithoutBindingMarksServiceCallReady() {
    String prompt =
        """
        Create chain named "PetLookup".
        HTTP GET "/pets/{id}" calls a backend pet API and returns the response.
        Plan structure only: http-trigger -> service-call.
        No security, no routing, no retry, no timeout.""";
    InMemorySkillWorkspace workspace =
        workspaceWithGraph(serviceCallSkeletonGraph(), prompt, prompt);

    GeneratorPlanManifest manifest =
        builder
            .build(policy, generationSkillIds, workspace)
            .manifest();

    assertEquals(GeneratorPlanStatus.READY, statusFor(manifest, "cip-service-call-generator"));
    assertTrue(
        matchedSignals(manifest, "cip-service-call-generator")
            .contains("incomplete_service_call_bindings"));
  }

  private static GeneratorPlanStatus statusFor(GeneratorPlanManifest manifest, String skillId) {
    return manifest.plans().stream()
        .filter(plan -> skillId.equals(plan.skillId()))
        .map(GeneratorPlan::status)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> matchedSignals(GeneratorPlanManifest manifest, String skillId) {
    return manifest.plans().stream()
        .filter(plan -> skillId.equals(plan.skillId()))
        .map(GeneratorPlan::matchedSignals)
        .findFirst()
        .orElseThrow();
  }

  private static InMemorySkillWorkspace workspaceWithGraph(
      ChainPlanGraph graph, String request, String brief) {
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("conv-manifest-test");
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "seed",
            new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "seed",
            new SkillArtifactPayload.RawUserRequestPayload(request, List.of())));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.REQUIREMENT_BRIEF,
            "seed",
            new SkillArtifactPayload.RequirementBriefPayload(
                new org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief(
                    brief, List.of(), List.of(), List.of(), List.of(), brief))));
    return workspace;
  }

  private static InMemorySkillWorkspace workspaceWithStructuredBrief(
      ChainPlanGraph graph,
      String request,
      org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief brief) {
    InMemorySkillWorkspace workspace = new InMemorySkillWorkspace("conv-manifest-test");
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.CHAIN_PLAN_GRAPH,
            "seed",
            new SkillArtifactPayload.ChainPlanGraphPayload(graph)));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.RAW_USER_REQUEST,
            "seed",
            new SkillArtifactPayload.RawUserRequestPayload(request, List.of())));
    workspace.put(
        SkillArtifact.of(
            SkillArtifactType.REQUIREMENT_BRIEF,
            "seed",
            new SkillArtifactPayload.RequirementBriefPayload(brief)));
    return workspace;
  }

  private static ChainPlanGraph greetingsE2eGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("greetings", "Greetings Chain"),
        List.of(
            new ChainPlanNode(
                "1",
                "http-trigger",
                "HTTP Trigger",
                null,
                null,
                List.of()),
            new ChainPlanNode("2", "script", "Return Greeting Script", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "1", "2", null)));
  }

  private static ChainPlanGraph greetingsGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("greetings", "Greetings"),
        List.of(
            new ChainPlanNode("1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("2", "script", "Script", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "1", "2", null)));
  }

  private static ChainPlanGraph secureHelloGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("secure-hello", "SecureHello"),
        List.of(
            new ChainPlanNode(
                "1",
                "http-trigger",
                "Trigger",
                null,
                null,
                List.of(new PlanProperty("externalRoute", "true"))),
            new ChainPlanNode("2", "script", "Script", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "1", "2", null)));
  }

  private static ChainPlanGraph serviceCallSkeletonGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("PetLookup", "Pet lookup"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "call-pets", "service-call", "Call pets API", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger", "call-pets", null)));
  }

  private static String fortunePrompt() {
    return """
        Create chain named "Fortune API".
        HTTP GET trigger on internal route "/fortune".
        Flow: script reads query param "lang" → condition/if/else routing by preferredLang == 'ru' vs else.
        Use condition/if/else (v2), not choice/when.""";
  }

  private static ChainPlanGraph fortuneCompleteRoutingGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Fortune API", "Fortune API with language routing"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "HTTP Trigger", null, null, List.of()),
            new ChainPlanNode("parse-lang", "script", "Parse lang", null, null, List.of()),
            new ChainPlanNode("route", "condition", "Route by language", null, null, List.of()),
            new ChainPlanNode(
                "if-ru",
                "if",
                "Russian branch",
                "route",
                null,
                List.of(new PlanProperty("condition", "${exchangeProperty.preferredLang} == 'ru'"))),
            new ChainPlanNode("else-en", "else", "Default branch", "route", null, List.of()),
            new ChainPlanNode("ru-response", "script", "RU response", "if-ru", null, List.of()),
            new ChainPlanNode("en-response", "script", "EN response", "else-en", null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger", "parse-lang", null),
            new ChainPlanEdge("e2", "parse-lang", "route", null)));
  }

  private static ChainPlanGraph fortuneWithCompleteTryCatchGraph() {
    List<ChainPlanNode> nodes = new ArrayList<>(fortuneCompleteRoutingGraph().nodes());
    nodes.addAll(
        List.of(
            new ChainPlanNode(
                "eh", "try-catch-finally-2", "Error Handling", null, null, List.of()),
            new ChainPlanNode("try", "try-2", "Try", "eh", null, List.of()),
            new ChainPlanNode(
                "catch",
                "catch-2",
                "Catch",
                "eh",
                null,
                List.of(new PlanProperty("exception", "java.lang.Exception")))));
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Fortune API", "Fortune API with language routing and EH"),
        nodes,
        fortuneCompleteRoutingGraph().edges());
  }

  private static ChainPlanGraph linearFortuneIntentGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Fortune API", "Fortune API linear draft"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "HTTP Trigger", null, null, List.of()),
            new ChainPlanNode("parse-lang", "script", "Parse lang", null, null, List.of()),
            new ChainPlanNode("ru-response", "script", "RU response", null, null, List.of()),
            new ChainPlanNode("en-response", "script", "EN response", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger", "parse-lang", null),
            new ChainPlanEdge("e2", "parse-lang", "ru-response", null)));
  }

  private static ChainPlanGraph incompleteRoutingGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Fortune API", "Incomplete routing"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "HTTP Trigger", null, null, List.of()),
            new ChainPlanNode("parse-lang", "script", "Parse lang", null, null, List.of()),
            new ChainPlanNode("route", "condition", "Route by language", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger", "parse-lang", null)));
  }
}
