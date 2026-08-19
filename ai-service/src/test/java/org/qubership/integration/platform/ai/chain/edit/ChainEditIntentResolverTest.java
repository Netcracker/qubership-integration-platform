package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.output.OutputParsingException;
import java.util.List;
import org.junit.jupiter.api.Test;
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
                ChainEditPlacement.UNSET,
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
                ChainEditPlacement.UNSET,
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
                  "placement": "UNSET",
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
                ChainEditPlacement.ROOT_TRIGGER,
                List.of()));

    assertEquals(ChainEditAction.ADD_ELEMENTS, intent.action());
    assertEquals("quartz-scheduler", intent.requestedElementType());
    assertEquals("0 */5 * * * ?", intent.cronExpression());
    assertEquals(ChainEditPlacement.ROOT_TRIGGER, intent.placement());
    assertEquals(List.of(), intent.targetNodeIds());
    assertTrue(intent.resolved(), intent.unresolvedAmbiguities().toString());
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
                ChainEditPlacement.ROOT_TRIGGER,
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
                ChainEditAction.EDIT_SCRIPT,
                List.of(),
                "return the customer id in the body",
                null,
                null,
                null,
                ChainEditPlacement.UNSET,
                List.of()));

    assertFalse(intent.resolved());
    assertEquals(List.of("Say which element to change."), intent.unresolvedAmbiguities());
  }

  @Test
  void aTimeoutOnTwoHttpTriggersAsksWhichWhenTheCaptureOmitsTheTarget() {
    ChainEditIntent intent =
        new ChainEditIntentResolver(
                (elements, userRequest) ->
                    capture(
                        ChainEditAction.EDIT_TIMEOUT,
                        List.of(),
                        "wait longer",
                        null,
                        null,
                        null,
                        ChainEditPlacement.UNSET,
                        List.of("http-a (HTTP trigger)", "http-b (HTTP trigger)")))
            .resolve(twoHttpTriggers(), "change timeout on the http trigger");

    assertFalse(intent.resolved());
    assertEquals(List.of(), intent.targetNodeIds());
    assertEquals(
        List.of("http-a (HTTP trigger)", "http-b (HTTP trigger)"),
        intent.unresolvedAmbiguities());
  }

  @Test
  void anAddWithoutPlacementAsksWhereToPlaceNotWhichElementToChange() {
    ChainEditIntent intent =
        resolve(
            capture(
                ChainEditAction.ADD_ELEMENTS,
                List.of(),
                "start every 5 minutes",
                null,
                "quartz-scheduler",
                "0 */5 * * * ?",
                ChainEditPlacement.UNSET,
                List.of()));

    assertFalse(intent.resolved());
    assertEquals(List.of("Say where to place the new element."), intent.unresolvedAmbiguities());
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
                ChainEditPlacement.ROOT_TRIGGER,
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

  private static ChainEditIntent resolve(ChainEditCapture capture) {
    return resolve("change something", capture);
  }

  private static ChainEditIntent resolve(String userRequest, ChainEditCapture capture) {
    return new ChainEditIntentResolver((elements, request) -> capture).resolve(graph(), userRequest);
  }

  private static ChainEditCapture capture(
      ChainEditAction action, List<String> targets, String change) {
    return capture(action, targets, change, null, null, null, ChainEditPlacement.UNSET, List.of());
  }

  private static ChainEditCapture capture(
      ChainEditAction action,
      List<String> targets,
      String change,
      String lookup,
      String elementType,
      String cron,
      ChainEditPlacement placement,
      List<String> ambiguities) {
    return new ChainEditCapture(
        action, targets, change, lookup, elementType, cron, placement, ambiguities);
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
