package org.qubership.integration.platform.ai.a2a.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Recovery of a conversation identifier a caller wrote into the message text instead of setting
 * {@code message.contextId}.
 *
 * <p>The pattern has to be tight in both directions: it must catch the phrasing a model actually
 * produces when told to continue a conversation, and it must ignore a bare UUID that happens to
 * appear in a requirements text.
 */
class QuotedContextIdTest {

  private static final String ID = "e12c7bd8-12bd-4834-a106-4a5bdda5cc59";

  @Test
  void recoversTheIdentifierFromThePhrasingObservedOnTheWire() {
    assertEquals(
        ID,
        QipAssistA2aAgentExecutor.findQuotedContextId(
            "Continue task with contextId " + ID + " for creating chain: Trigger HTTP GET /hello"));
  }

  @Test
  void acceptsCommonLabelSpellingsAndPunctuation() {
    assertEquals(ID, QipAssistA2aAgentExecutor.findQuotedContextId("contextId: " + ID));
    assertEquals(ID, QipAssistA2aAgentExecutor.findQuotedContextId("context_id=" + ID));
    assertEquals(ID, QipAssistA2aAgentExecutor.findQuotedContextId("Context ID \"" + ID + "\""));
    assertEquals(ID, QipAssistA2aAgentExecutor.findQuotedContextId("context-id (" + ID + ")"));
  }

  @Test
  void ignoresABareUuidWithNoLabel() {
    assertNull(
        QipAssistA2aAgentExecutor.findQuotedContextId(
            "Create a chain for correlation id " + ID + " received from the upstream system"));
  }

  /** A label far from the value is prose about identifiers, not a continuation request. */
  @Test
  void ignoresALabelDetachedFromTheValue() {
    assertNull(
        QipAssistA2aAgentExecutor.findQuotedContextId(
            "The contextId should be preserved across turns. Some unrelated run used " + ID));
  }

  @Test
  void toleratesMissingOrEmptyText() {
    assertNull(QipAssistA2aAgentExecutor.findQuotedContextId(null));
    assertNull(QipAssistA2aAgentExecutor.findQuotedContextId("   "));
  }
}
