package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.output.OutputParsingException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.llm.agent.ChainEditIntentAgent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

class ChainEditIntentResolverTest {

  @Test
  void readsTheActionTheTargetsAndTheLookup() {
    ChainEditIntent intent =
        resolve(
            capture(
                ChainEditAction.REBIND_SERVICE_CALL,
                List.of("call-orders"),
                "point it at the order-status operation",
                "order status",
                null,
                null,
                List.of()));

    assertEquals(ChainEditAction.REBIND_SERVICE_CALL, intent.action());
    assertEquals(List.of("call-orders"), intent.targetNodeIds());
    assertEquals("point it at the order-status operation", intent.requestedChange());
    assertEquals("order status", intent.externalBindingQuery());
    assertTrue(intent.resolved());
  }

  @Test
  void aTargetTheChainDoesNotHaveBecomesAQuestionRatherThanATarget() {
    ChainEditIntent intent =
        resolve(
            capture(
                ChainEditAction.REBIND_SERVICE_CALL,
                List.of("call-shipping"),
                "rebind it",
                null,
                null,
                null,
                List.of()));

    assertEquals(List.of(), intent.targetNodeIds());
    assertFalse(intent.resolved());
    assertEquals(List.of("The chain has no element 'call-shipping'."), intent.unresolvedAmbiguities());
  }

  @Test
  void aNullActionIsNoChange() {
    ChainEditIntent intent = resolve(capture(null, List.of("call-orders"), "do it all"));

    assertEquals(ChainEditAction.NO_CHANGE, intent.action());
    assertTrue(intent.resolved());
    assertEquals(List.of(), intent.targetNodeIds());
  }

  @Test
  void anEmptyActionStringDoesNotThrowOutputParsingException() throws Exception {
    ChainEditCapture capture =
        new ObjectMapper()
            .readValue(
                """
                {
                  "action": "",
                  "targetNodeIds": [],
                  "requestedChange": "No changes requested.",
                  "lookup": "",
                  "elementType": "",
                  "cronExpression": "",
                  "ambiguities": []
                }
                """,
                ChainEditCapture.class);

    ChainEditIntent intent = resolve(capture);

    assertEquals(ChainEditAction.NO_CHANGE, capture.action());
    assertEquals(ChainEditAction.NO_CHANGE, intent.action());
    assertTrue(intent.resolved());
  }

  @Test
  void anUnknownActionNameIsNoChange() throws Exception {
    ChainEditCapture capture =
        new ObjectMapper()
            .readValue(
                """
                {"action":"NOT_AN_ACTION","targetNodeIds":[],"requestedChange":"","ambiguities":[]}
                """,
                ChainEditCapture.class);

    assertEquals(ChainEditAction.NO_CHANGE, capture.action());
    assertTrue(resolve(capture).resolved());
  }

  @Test
  void aParserFailureIsNoChangeRatherThanThrown() {
    ChainEditIntent intent =
        new ChainEditIntentResolver(
                (elements, userRequest) -> {
                  throw new OutputParsingException(
                      "Failed to parse response into org.qubership.integration.platform.ai"
                          + ".chain.edit.ChainEditCapture",
                      new IllegalArgumentException(
                          "Cannot coerce empty string (\"\") to ChainEditAction"));
                })
            .resolve(graph(), "ok");

    assertEquals(ChainEditAction.NO_CHANGE, intent.action());
    assertTrue(intent.resolved());
  }

  @Test
  void addingAQuartzSchedulerDoesNotRequireAnExistingElement() {
    ChainEditIntent intent =
        resolve(
            "schedule this every 5 minutes",
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of(),
                "start every 5 minutes",
                null,
                "quartz-scheduler",
                "0 */5 * * * ?",
                List.of()));

    assertEquals(ChainEditAction.ADD_ELEMENTS, intent.action());
    assertEquals("quartz-scheduler", intent.requestedElementType());
    assertEquals("0 */5 * * * ?", intent.cronExpression());
    assertTrue(intent.isRootTrigger());
    assertEquals(ChainEditDisposition.UNSET, intent.disposition());
    assertEquals(List.of(), intent.targetNodeIds());
    assertTrue(intent.resolved(), intent.unresolvedAmbiguities().toString());
    assertFalse(intent.requiresStructureStage());
  }

  @Test
  void anAddCaptureIsCompleteFromTheSchemaNotFromTheUserWording() {
    ChainEditIntent intent =
        resolve(
            "please change the timeout",
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of(),
                "start every 5 minutes",
                null,
                "quartz-scheduler",
                "0 */5 * * * ?",
                List.of()));

    assertTrue(intent.resolved(), intent.unresolvedAmbiguities().toString());
    assertEquals(ChainEditAction.ADD_ELEMENTS, intent.action());
  }

  @Test
  void anEditWithNoTargetStillAsksWhichElementToChange() {
    ChainEditIntent intent =
        resolve(
            "add quartz-scheduler every 5 minutes",
            capture(
                ChainEditAction.CONFIGURE,
                List.of(),
                "return the customer id in the body",
                null,
                null,
                null,
                List.of("script"),
                List.of()));

    assertFalse(intent.resolved());
    assertEquals(List.of("Say which element to change."), intent.unresolvedAmbiguities());
  }

  @Test
  void aConfigureCaptureWithoutPropertyKeysAsksWhichPropertiesShouldChange() {
    ChainEditIntent intent =
        resolve(
            capture(
                ChainEditAction.CONFIGURE,
                List.of("call-orders"),
                "change the endpoint",
                null,
                null,
                null,
                List.of()));

    assertFalse(intent.resolved());
    assertEquals(List.of("Say which properties should change."), intent.unresolvedAmbiguities());
  }

  @Test
  void aConfigureCaptureWithTargetsAndPropertyKeysIsResolved() {
    ChainEditIntent intent =
        resolve(
            capture(
                ChainEditAction.CONFIGURE,
                List.of("call-orders"),
                "change the endpoint",
                null,
                null,
                null,
                List.of("contextPath"),
                List.of()));

    assertTrue(intent.resolved(), intent.unresolvedAmbiguities().toString());
    assertEquals(List.of("contextPath"), intent.propertyKeys());
  }

  @Test
  void aConfigureOnTwoHttpTriggersAsksWhichWhenTheCaptureOmitsTheTarget() {
    ChainEditIntent intent =
        new ChainEditIntentResolver(
                (elements, userRequest) ->
                    capture(
                        ChainEditAction.CONFIGURE,
                        List.of(),
                        "wait longer",
                        null,
                        null,
                        null,
                        List.of("http-a (HTTP trigger)", "http-b (HTTP trigger)")))
            .resolve(twoHttpTriggers(), "change timeout on the http trigger");

    assertFalse(intent.resolved());
    assertEquals(List.of(), intent.targetNodeIds());
    assertEquals(
        List.of("http-a (HTTP trigger)", "http-b (HTTP trigger)"),
        intent.unresolvedAmbiguities());
  }

  @Test
  void anAddWithoutAnAddressAsksWhereToPlaceNotWhichElementToChange() {
    ChainEditIntent intent =
        resolve(
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of(),
                "add a script after something",
                null,
                "script",
                null,
                List.of()));

    assertFalse(intent.resolved());
    assertEquals(List.of("Say where to place the new element."), intent.unresolvedAmbiguities());
  }

  @Test
  void namingBothEndsOfAnInsertionAddressIsResolved() {
    ChainEditIntent intent =
        resolve(
            adjacentPairGraph(),
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of("call-orders", "call-invoices"),
                "add a script between the order call and the invoice call",
                null,
                "script",
                null,
                List.of()));

    assertTrue(intent.resolved(), intent.unresolvedAmbiguities().toString());
    assertEquals(List.of("call-orders", "call-invoices"), intent.targetNodeIds());
    assertEquals(ChainEditDisposition.KEEP, intent.disposition());
    assertTrue(intent.requiresStructureStage());
    assertFalse(intent.isRootTrigger());
  }

  @Test
  void namingOnlyThePrecedingElementIsResolvedWhenItHasOneSuccessor() {
    ChainEditIntent intent =
        resolve(
            adjacentPairGraph(),
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of("call-orders"),
                "add a script after the order call",
                null,
                "script",
                null,
                List.of()));

    assertTrue(intent.resolved(), intent.unresolvedAmbiguities().toString());
    assertEquals(List.of("call-orders"), intent.targetNodeIds());
  }

  @Test
  void namingOnlyAPrecedingElementWithSeveralSuccessorsAsksWhichBranchRatherThanPickingOne() {
    ChainEditIntent intent =
        resolve(
            branchingGraph(),
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of("call-orders"),
                "add a script after the order call",
                null,
                "script",
                null,
                List.of()));

    assertFalse(intent.resolved());
    assertEquals(List.of("call-orders"), intent.targetNodeIds());
    assertEquals(List.of("branch-a", "branch-b"), intent.unresolvedAmbiguities());
  }

  @Test
  void replacingAnElementDoesNotAskWhichSuccessorEvenWhenThatElementHasSeveral() {
    ChainEditIntent intent =
        resolve(
            branchingGraph(),
            new ChainEditCapture(
                ChainEditAction.ADD_ELEMENTS,
                List.of("call-orders"),
                "replace the order call with a script then a shipping call",
                null,
                "script",
                null,
                List.of(),
                List.of(),
                ChainEditDisposition.REMOVE));

    assertTrue(intent.resolved(), intent.unresolvedAmbiguities().toString());
    assertEquals(List.of("call-orders"), intent.targetNodeIds());
    assertEquals(ChainEditDisposition.REMOVE, intent.disposition());
  }

  @Test
  void anInsertionAddressNamingAnElementTheChainDoesNotHaveIsReportedAsUnresolved() {
    ChainEditIntent intent =
        resolve(
            adjacentPairGraph(),
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of("call-shipping"),
                "add a script after the shipping call",
                null,
                "script",
                null,
                List.of()));

    assertFalse(intent.resolved());
    assertEquals(List.of(), intent.targetNodeIds());
    assertEquals(List.of("The chain has no element 'call-shipping'."), intent.unresolvedAmbiguities());
  }

  @Test
  void anAddWithoutATypeAsksForTheTypeNotWhichElementToChange() {
    ChainEditIntent intent =
        resolve(
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of(),
                "add something",
                null,
                null,
                null,
                List.of()));

    assertFalse(intent.resolved());
    assertEquals(List.of("Say which element type to add."), intent.unresolvedAmbiguities());
  }

  @Test
  void elementsAreRenderedAsIdTypeAndLabel() {
    assertEquals(
        """
        call-orders | service-call | Call orders
        call-invoices | service-call | Call invoices
        """,
        ChainEditIntentResolver.renderElements(graph()));
  }

  @Test
  void resumingAClarificationPassesTheHeldCaptureAndQuestionToTheClassifierAsStructuredInput() {
    List<String> seenRequests = new ArrayList<>();
    ChainEditIntentAgent agent =
        (elements, userRequest) -> {
          seenRequests.add(userRequest);
          return capture(
              ChainEditAction.REBIND_SERVICE_CALL,
              List.of("call-invoices"),
              "point it at the order-status operation",
              "order status",
              null,
              null,
              List.of());
        };
    ChainEditIntent held =
        new ChainEditIntent(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(),
            "point it at the order-status operation",
            "order status",
            List.of("call-orders (Call orders)", "call-invoices (Call invoices)"));

    ChainEditIntent resumed =
        new ChainEditIntentResolver(agent)
            .resume(graph(), held, "Which one do you mean?", "the second one");

    assertEquals(List.of("call-invoices"), resumed.targetNodeIds());
    assertTrue(resumed.resolved());
    String sentRequest = seenRequests.get(0);
    assertTrue(sentRequest.contains("Which one do you mean?"), sentRequest);
    assertTrue(sentRequest.contains("the second one"), sentRequest);
    assertTrue(sentRequest.contains("call-orders (Call orders)"), sentRequest);
    assertTrue(sentRequest.contains("REBIND_SERVICE_CALL"), sentRequest);
    assertFalse(sentRequest.contains("placement:"), sentRequest);
  }

  @Test
  void aParserFailureDuringResumeIsNoChangeRatherThanThrown() {
    ChainEditIntent held =
        new ChainEditIntent(
            ChainEditAction.REBIND_SERVICE_CALL,
            List.of(),
            "point it at the order-status operation",
            "order status",
            List.of("call-orders (Call orders)", "call-invoices (Call invoices)"));
    ChainEditIntent intent =
        new ChainEditIntentResolver(
                (elements, userRequest) -> {
                  throw new OutputParsingException(
                      "Failed to parse response into org.qubership.integration.platform.ai"
                          + ".chain.edit.ChainEditCapture",
                      new IllegalArgumentException(
                          "Cannot coerce empty string (\"\") to ChainEditAction"));
                })
            .resume(graph(), held, "Which one do you mean?", "the second one");

    assertEquals(ChainEditAction.NO_CHANGE, intent.action());
    assertTrue(intent.resolved());
  }

  private static ChainEditIntent resolve(ChainEditCapture capture) {
    return resolve("change something", capture);
  }

  private static ChainEditIntent resolve(String userRequest, ChainEditCapture capture) {
    return new ChainEditIntentResolver((elements, request) -> capture).resolve(graph(), userRequest);
  }

  private static ChainEditIntent resolve(ChainPlanGraph graph, ChainEditCapture capture) {
    return new ChainEditIntentResolver((elements, request) -> capture)
        .resolve(graph, "change something");
  }

  private static ChainEditCapture capture(
      ChainEditAction action, List<String> targets, String change) {
    return capture(action, targets, change, null, null, null, List.of());
  }

  private static ChainEditCapture capture(
      ChainEditAction action,
      List<String> targets,
      String change,
      String lookup,
      String elementType,
      String cron,
      List<String> ambiguities) {
    return capture(action, targets, change, lookup, elementType, cron, List.of(), ambiguities);
  }

  private static ChainEditCapture capture(
      ChainEditAction action,
      List<String> targets,
      String change,
      String lookup,
      String elementType,
      String cron,
      List<String> propertyKeys,
      List<String> ambiguities) {
    return new ChainEditCapture(
        action, targets, change, lookup, elementType, cron, propertyKeys, ambiguities);
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("call-orders", "service-call", "Call orders", null, null, List.of()),
            new ChainPlanNode(
                "call-invoices", "service-call", "Call invoices", null, null, List.of())),
        List.of());
  }

  private static ChainPlanGraph adjacentPairGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("call-orders", "service-call", "Call orders", null, null, List.of()),
            new ChainPlanNode(
                "call-invoices", "service-call", "Call invoices", null, null, List.of())),
        List.of(new ChainPlanEdge("edge-1", "call-orders", "call-invoices", null)));
  }

  private static ChainPlanGraph branchingGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("call-orders", "service-call", "Call orders", null, null, List.of()),
            new ChainPlanNode("branch-a", "script", "Branch A", null, null, List.of()),
            new ChainPlanNode("branch-b", "script", "Branch B", null, null, List.of())),
        List.of(
            new ChainPlanEdge("edge-a", "call-orders", "branch-a", null),
            new ChainPlanEdge("edge-b", "call-orders", "branch-b", null)));
  }

  private static ChainPlanGraph twoHttpTriggers() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("http-a", "http-trigger", "HTTP trigger", null, null, List.of()),
            new ChainPlanNode("http-b", "http-trigger", "HTTP trigger", null, null, List.of()),
            new ChainPlanNode("script", "script", "Work", null, null, List.of())),
        List.of());
  }
}
