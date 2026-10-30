package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import org.qubership.integration.platform.ai.catalog.binding.CompositionCatalogBinder;
import org.qubership.integration.platform.ai.catalog.binding.McpSystemCatalogBinder;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.CapabilityInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.DraftSettings;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.FlowInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.InteractionInput;
import org.qubership.integration.platform.ai.plan.RequirementCaptureInput.QuestionInput;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.productpipeline.recovery.AuthorQuestionEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;

class RequirementTargetResolverTest {

  @Test
  void compositeReferenceReturnsVerifiedTriggerIdsWithoutAcceptingIt() {
    var chains = mock(CompositionCatalogBinder.class);
    var first = new CatalogElementResponseDto();
    first.id = "old-trigger";
    first.chainName = "Target Chain";
    var second = new CatalogElementResponseDto();
    second.id = "new-trigger";
    second.chainName = "Target Chain";
    var unrelated = new CatalogElementResponseDto();
    unrelated.id = "unrelated-trigger";
    unrelated.chainName = "Another Chain";
    when(chains.listChainTriggers()).thenReturn(List.of(first, second, unrelated));
    var resolver = new RequirementTargetResolver(chains, mock(McpSystemCatalogBinder.class));

    var rejected = resolver.resolve(draft("chain-id/new-trigger"));
    assertTrue(rejected.targetIds().isEmpty());
    var issue = rejected.issues().getFirst();
    assertEquals("INVALID_TARGET_SELECTION", issue.code());
    assertTrue(issue.message().contains("targetReference"), issue.message());
    assertTrue(issue.message().contains("old-trigger") && issue.message().contains("new-trigger"), issue.message());
    assertTrue(!issue.message().contains("unrelated-trigger"), issue.message());
    assertEquals("new-trigger", resolver.resolve(draft("new-trigger")).targetIds().get("call"));
    assertTrue(resolver.resolve(draft("unrelated-trigger")).targetIds().isEmpty());
    assertTrue(resolver.resolve(draft(null)).targetIds().isEmpty());
  }

  @Test
  void chainTargetMustMatchAnExistingTriggerForTheNamedChain() {
    CompositionCatalogBinder chains = mock(CompositionCatalogBinder.class);
    CatalogElementResponseDto trigger = new CatalogElementResponseDto();
    trigger.id = "verified-trigger";
    trigger.chainName = "Target Chain";
    when(chains.listChainTriggers()).thenReturn(List.of(trigger));
    RequirementTargetResolver resolver = new RequirementTargetResolver(
        chains, mock(McpSystemCatalogBinder.class));

    assertEquals("verified-trigger", resolver.resolve(draft(null)).targetIds().get("call"));
    assertEquals("verified-trigger", resolver.resolve(draft("verified-trigger"))
        .targetIds().get("call"));
    assertTrue(resolver.resolve(draft("invented-trigger")).issues().stream()
        .anyMatch(issue -> "INVALID_TARGET_SELECTION".equals(issue.code())));
  }

  @Test
  void aNamedChainWithNoTriggerAsksTheUserInsteadOfInventingAnId() {
    RequirementTargetResolver resolver = new RequirementTargetResolver(
        mock(CompositionCatalogBinder.class), mock(McpSystemCatalogBinder.class));

    var missing = resolver.resolve(draft(null));
    assertTrue(missing.targetIds().isEmpty());
    var issue = missing.issues().getFirst();
    assertEquals("INVALID_TARGET_SELECTION", issue.code());
    assertTrue(issue.message().contains("Target Chain"), issue.message());
    assertTrue(issue.message().contains("Ask which catalog chain to call"), issue.message());
    assertTrue(!issue.message().contains("Set targetReference"), issue.message());
    var invented = resolver.resolve(draft("invented-trigger")).issues().getFirst();
    assertEquals("INVALID_TARGET_SELECTION", invented.code());
    assertTrue(invented.message().contains("Do not invent a trigger id."), invented.message());
    assertTrue(!invented.message().contains("Set targetReference"), invented.message());
  }

  @Test
  void resolveChainCallAnswerUsesListedTriggersAndWritesOneElementId() {
    var chains = mock(CompositionCatalogBinder.class);
    var matcher = mock(CatalogBindingMatcher.class);
    var systems = mock(McpSystemCatalogBinder.class);
    var trigger = chainTrigger("trigger-element", "chain-1", "Billing Chain", "Start");
    var other = chainTrigger("other-trigger", "chain-2", "Billing Chain", "Start");
    var operation = chainTrigger("operation-42", "chain-9", "Operations", "Get order");
    operation.type = "service-call";
    when(chains.listChainTriggers()).thenReturn(List.of(trigger, other, operation));
    var resolver = new RequirementTargetResolver(chains, systems);
    var question = chainQuestion("Select trigger-element for this chain call.");

    assertEquals("trigger-element",
        resolver.resolveChainCallAnswer(draft(null), question, "trigger-element").targetIds().get("call"),
        "resolveChainCallAnswer(trigger-element)");
    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, "chain-1").targetIds().isEmpty(),
        "resolveChainCallAnswer(chain-1)");
    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, "invented-trigger").targetIds().isEmpty(),
        "resolveChainCallAnswer(invented-trigger)");
    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, "operation-42").targetIds().isEmpty(),
        "resolveChainCallAnswer(operation-42)");
    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, "Billing Chain").targetIds().isEmpty(),
        "resolveChainCallAnswer(Billing Chain)");
    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, question.text()).targetIds().isEmpty(),
        "resolveChainCallAnswer(question text)");
    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, "please use trigger-element now")
        .targetIds().isEmpty(), "resolveChainCallAnswer(prose containing the id)");
    verify(chains, atLeastOnce()).listChainTriggers();
    verifyNoMoreInteractions(chains);
    verifyNoInteractions(matcher);
    verifyNoInteractions(systems);
    assertTrue(Arrays.stream(RequirementTargetResolver.class.getDeclaredFields())
        .noneMatch(field -> CatalogBindingMatcher.class.equals(field.getType())),
        "RequirementTargetResolver fields");
  }

  @Test
  void resolveChainCallAnswerIgnoresAnswersWhenEvidenceIsNotCatalogChoice() {
    var chains = mock(CompositionCatalogBinder.class);
    var trigger = chainTrigger("trigger-element", "chain-1", "Billing Chain", "Start");
    when(chains.listChainTriggers()).thenReturn(List.of(trigger));
    var resolver = new RequirementTargetResolver(chains, mock(McpSystemCatalogBinder.class));
    var question = new QuestionInput("target", List.of("call"), "Pick a trigger",
        new AuthorQuestionEvidence("call", "targetReference", AuthorQuestionEvidence.Kind.MISSING_VALUE,
            List.of("turn-1")));

    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, "trigger-element").targetIds().isEmpty());
  }

  @Test
  void resolveChainCallAnswerMatchesChainNameOnlyForListedChainTriggerType() {
    var chains = mock(CompositionCatalogBinder.class);
    var listed = chainTrigger("listed-trigger", "chain-1", "Billing Chain", "Start");
    var blankType = new CatalogElementResponseDto();
    blankType.id = "blank-type-trigger";
    blankType.chainId = "chain-1";
    blankType.chainName = "Billing Chain";
    blankType.name = "Start";
    blankType.type = "";
    when(chains.listChainTriggers()).thenReturn(List.of(listed, blankType));
    var resolver = new RequirementTargetResolver(chains, mock(McpSystemCatalogBinder.class));
    var question = chainQuestion("Which chain-trigger element id should this chain call use?");

    assertEquals("listed-trigger",
        resolver.resolveChainCallAnswer(draft(null), question, "Billing Chain").targetIds().get("call"));
  }

  @Test
  void listedChainTriggerAcceptsOnlyVerifiedTriggerElementIds() {
    var chains = mock(CompositionCatalogBinder.class);
    when(chains.listChainTriggers()).thenReturn(List.of(
        chainTrigger("listed-trigger", "chain-1", "Billing Chain", "Start")));
    var resolver = new RequirementTargetResolver(chains, mock(McpSystemCatalogBinder.class));

    assertTrue(resolver.listedChainTrigger("listed-trigger"));
    assertTrue(!resolver.listedChainTrigger("not-a-trigger"));
    assertTrue(!resolver.listedChainTrigger(""));
    assertTrue(!resolver.listedChainTrigger("chain/id"));
  }

  @Test
  void resolveChainCallAnswerAcceptsOneChainNameAsThatTriggerElement() {
    var chains = mock(CompositionCatalogBinder.class);
    var trigger = chainTrigger("only-trigger", "chain-1", "Billing Chain", "Start");
    when(chains.listChainTriggers()).thenReturn(List.of(trigger));
    var resolver = new RequirementTargetResolver(chains, mock(McpSystemCatalogBinder.class));
    var question = chainQuestion("Which chain-trigger element id should this chain call use?");

    assertEquals("only-trigger",
        resolver.resolveChainCallAnswer(draft(null), question, "billing chain").targetIds().get("call"),
        "resolveChainCallAnswer(billing chain)");
    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, "Billing").targetIds().isEmpty(),
        "resolveChainCallAnswer(Billing)");
    assertTrue(resolver.resolveChainCallAnswer(draft(null), question, "chain-1").targetIds().isEmpty(),
        "resolveChainCallAnswer(chain-1)");
  }

  private static QuestionInput chainQuestion(String text) {
    return new QuestionInput("target", List.of("call"), text,
        new AuthorQuestionEvidence("call", "targetReference", AuthorQuestionEvidence.Kind.CATALOG_CHOICE,
            List.of("turn-1")));
  }

  private static CatalogElementResponseDto chainTrigger(
      String id, String chainId, String chainName, String name) {
    var trigger = new CatalogElementResponseDto();
    trigger.id = id;
    trigger.chainId = chainId;
    trigger.chainName = chainName;
    trigger.name = name;
    trigger.type = "chain-trigger-2";
    return trigger;
  }

  private static DraftInput draft(String reference) {
    return new DraftInput(new FlowInput(List.of(
        new InteractionInput("call", Direction.OUTBOUND, "Target Chain", "Invoke",
            null, null, null)), List.of()), List.of(), List.of(
        new CapabilityInput("call", "chain-call-2", null, null, null,
            null, null, reference)), List.of(), new DraftSettings(null, null));
  }
}
