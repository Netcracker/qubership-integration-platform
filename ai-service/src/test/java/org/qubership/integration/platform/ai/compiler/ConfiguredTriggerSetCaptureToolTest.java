package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureKey;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSlot;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class ConfiguredTriggerSetCaptureToolTest {

  private static final String CONVERSATION_ID = "conv-trigger";

  private CaptureAttemptFeedbackStore feedbackStore;
  private CaptureSession session;
  private ConfiguredTriggerSetCaptureTool tool;

  @BeforeEach
  void setUp() {
    session = new CaptureSession();
    feedbackStore = new CaptureAttemptFeedbackStore();
    tool =
        new ConfiguredTriggerSetCaptureTool(
            session,
            new ObjectMapper(),
            feedbackStore,
            DeterministicElementSchemaService.createForUnitTests(new ObjectMapper()));
    org.jboss.logmanager.MDC.put(ChatMdc.CONVERSATION_ID, CONVERSATION_ID);
  }

  @Test
  void repeatIsBlockedBeforeTriggerValidation() {
    feedbackStore.beginScope(CONVERSATION_ID, "", java.util.Map.of("inputs", "v1"));
    var invalid = new ConfiguredTriggerSet(1, List.of(), List.of(), List.of());
    assertTrue(tool.captureConfiguredTriggerSet(invalid).contains("triggers"));
    assertThrows(org.qubership.integration.platform.ai.compiler.capture.CaptureNoProgressException.class,
        () -> tool.captureConfiguredTriggerSet(invalid));
    assertFalse(session.isPresent(CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID)));
  }

  @Test
  void capturesConfiguredTriggerSet() {
    CaptureValidationException terminal =
        assertThrows(
            CaptureValidationException.class, () -> tool.captureConfiguredTriggerSet(validTriggerSet()));

    assertTrue(terminal.getMessage().contains("captured"));
    assertEquals(
        1,
        session
            .get(
                CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID),
                ConfiguredTriggerSet.class)
            .orElseThrow()
            .triggers()
            .size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "9000"})
  void capturesApprovedAsyncApiPropertiesBeforeSchemaValidation(String generatedDelay) {
    var intent = new RequirementElementIntent("trigger", "async-api-trigger",
        RequirementElementIntent.Placement.ANCHOR, "trigger", null, null, null,
        List.of(new PlanProperty("reconnectDelay", "30000"),
            new PlanProperty("integrationOperationAsyncProperties", "{\"groupId\":\"CIP\"}")), List.of());
    var fact = new RequirementFact("trigger-options", RequirementFactPolarity.POSITIVE,
        RequirementFactKind.PARAMETER, "", "Use approved Kafka consumer options", "", "", "", "", "", "",
        List.of("trigger"), null, intent);
    session.set(CaptureKey.conversation(CaptureSlot.APPROVED_REQUIREMENT_BRIEF, CONVERSATION_ID),
        new RequirementBrief("Kafka to REST", List.of(), List.of(), List.of(), List.of(), "",
            null, "", List.of(fact)));
    var capture = new ConfiguredTriggerSet(1,
        List.of(new ConfiguredTrigger("entry", "trigger", "async-api-trigger", "Kafka event",
            List.of(new PlanProperty("integrationOperationProtocolType", "kafka"),
                new PlanProperty("reconnectDelay", generatedDelay),
                new PlanProperty("integrationOperationAsyncProperties",
                    "{\"groupId\":\"wrong\",\"maas.classifier.name\":\"topic2\"}")))), List.of(), List.of());

    CaptureValidationException accepted = assertThrows(CaptureValidationException.class,
        () -> tool.captureConfiguredTriggerSet(capture));

    assertTrue(accepted.getMessage().contains("captured"), accepted.getMessage());
    var properties = session.get(
        CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID),
        ConfiguredTriggerSet.class).orElseThrow().triggers().getFirst().properties();
    assertTrue(properties.contains(new PlanProperty("reconnectDelay", "30000")));
    assertTrue(properties.contains(new PlanProperty("integrationOperationAsyncProperties",
        "{\"groupId\":\"CIP\",\"maas.classifier.name\":\"topic2\"}")));
    assertTrue(capture.triggers().getFirst().properties()
        .contains(new PlanProperty("reconnectDelay", generatedDelay)));
  }

  @Test
  void rejectsBlankReconnectDelayWithoutAnApprovedValue() {
    var capture = new ConfiguredTriggerSet(1,
        List.of(new ConfiguredTrigger("entry", "trigger", "async-api-trigger", "Kafka event",
            List.of(new PlanProperty("reconnectDelay", "")))), List.of(), List.of());

    assertTrue(tool.captureConfiguredTriggerSet(capture).contains("reconnectDelay"));
    assertFalse(session.isPresent(
        CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID)));
  }

  @Test
  void rejectsConfiguredTriggerSetWithoutTriggers() {
    String result =
        tool.captureConfiguredTriggerSet(new ConfiguredTriggerSet(1, List.of(), List.of(), List.of()));

    assertTrue(result.contains("triggers"));
    assertFalse(
        session.isPresent(CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID)));
  }

  @Test
  void rejectsAllInvalidTriggerPropertiesBeforeCapture() {
    ConfiguredTriggerSet invalid =
        new ConfiguredTriggerSet(
            1,
            List.of(
                new ConfiguredTrigger(
                    "entry",
                    "http-trigger-1",
                    "http-trigger",
                    "Customer API",
                    List.of(
                        new PlanProperty("externalRoute", "external"),
                        new PlanProperty("privateRoute", "private"),
                        new PlanProperty("unknownProperty", "value")))),
            List.of(),
            List.of());

    String result = tool.captureConfiguredTriggerSet(invalid);

    assertTrue(result.contains("externalRoute"), result);
    assertTrue(result.contains("privateRoute"), result);
    assertTrue(result.contains("unknownProperty"), result);
    assertFalse(
        session.isPresent(CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID)));

    CaptureValidationException accepted =
        assertThrows(
            CaptureValidationException.class, () -> tool.captureConfiguredTriggerSet(validTriggerSet()));

    assertTrue(accepted.getMessage().contains("captured"), accepted.getMessage());
    assertTrue(
        session.isPresent(CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID)));
  }

  @Test
  void duplicateCapturePreservesFirstTriggerSet() {
    ConfiguredTriggerSet first = validTriggerSet();
    assertThrows(CaptureValidationException.class, () -> tool.captureConfiguredTriggerSet(first));

    assertThrows(
        CaptureValidationException.class,
        () ->
            tool.captureConfiguredTriggerSet(
                new ConfiguredTriggerSet(
                    1,
                    List.of(new ConfiguredTrigger("entry", "entry-2", "http-trigger", "Other", List.of())),
                    List.of(),
                    List.of())));
    assertEquals(
        first,
        session
            .get(
                CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID),
                ConfiguredTriggerSet.class)
            .orElseThrow());
  }

  @Test
  void captureSessionRejectsWrongRuntimeTypeForTriggerSlot() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                session.accept(
                    CaptureKey.conversation(CaptureSlot.CONFIGURED_TRIGGER_SET, CONVERSATION_ID),
                    "not-trigger-set",
                    "ok",
                    "dup"));

    assertTrue(thrown.getMessage().contains("does not match slot"));
  }

  private static ConfiguredTriggerSet validTriggerSet() {
    return new ConfiguredTriggerSet(
        1,
        List.of(
            new ConfiguredTrigger(
                "entry",
                "http-trigger-1",
                "http-trigger",
                "Customer API",
                List.of(new PlanProperty("contextPath", "/api/customers")))),
        List.of("fact-1"),
        List.of());
  }
}
