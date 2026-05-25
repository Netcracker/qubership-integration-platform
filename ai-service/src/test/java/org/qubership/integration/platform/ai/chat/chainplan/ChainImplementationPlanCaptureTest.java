package org.qubership.integration.platform.ai.chat.chainplan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainImplementationPlanCaptureTest {

  @Test
  void findLatestPlanJsonPrefersLastValidBlock() {
    String assistant =
        """
Intro text
```json
{"chain":{"name":"old"},"elements":[{"clientId":"a","type":"http-trigger"}],"connections":[]}
```
More text
```json
{"chain":{"name":"new"},"elements":[{"clientId":"b","type":"script"}],"connections":[]}
```
""";
    Optional<String> json = ChainImplementationPlanCapture.findLatestPlanJson(assistant);
    assertTrue(json.isPresent());
    assertTrue(json.get().contains("\"new\""), json.get());
  }

  @Test
  void findLatestPlanJsonAcceptsFenceWithoutJsonLanguageTag() {
    String assistant =
        """
```
{"chain":{"name":"plain"},"elements":[{"clientId":"x","type":"http-trigger"}],"connections":[]}
```
""";
    Optional<String> json = ChainImplementationPlanCapture.findLatestPlanJson(assistant);
    assertTrue(json.isPresent());
    assertTrue(json.get().contains("\"plain\""), json.get());
  }

  @Test
  void findLatestPlanJsonEmptyWhenNoPlan() {
    assertTrue(ChainImplementationPlanCapture.findLatestPlanJson("Just prose").isEmpty());
  }

  @Test
  void findLatestPlanJsonSkipsTrailingNonPlanFence() {
    String assistant =
        """
        ```json
        {"chain":{"name":"plan"},"elements":[{"clientId":"a","type":"http-trigger"}],"connections":[]}
        ```
        trailing note
        ```
        {"debug": true}
        ```
        """;
    Optional<String> json = ChainImplementationPlanCapture.findLatestPlanJson(assistant);
    assertTrue(json.isPresent());
    assertTrue(json.get().contains("\"plan\""), json.get());
  }

  @Test
  void findLatestPlanJsonEmptyWhenLatestPlanShapedBlockIncomplete() {
    String assistant =
        """
        ```json
        {"chain":{"name":"old"},"elements":[{"clientId":"a","type":"http-trigger"}],"connections":[]}
        ```
        ```json
        {"chain":{"name":"new"},"elements":[{"clientId":"b","type":"script"
        """;
    assertTrue(ChainImplementationPlanCapture.findLatestPlanJson(assistant).isEmpty());
  }

  @Test
  void findLatestPlanJsonEmptyWhenJsonTruncatedDuringStreaming() {
    String partial =
        """
        ```json
        {
          "chain": { "name": "Partial", "description": "" },
          "elements": [
            { "clientId": "http-trigger-1", "type": "http-trigger",
              "expectedProperties": { "uri": "http://example
        """;
    assertTrue(ChainImplementationPlanCapture.findLatestPlanJson(partial).isEmpty());
  }

  @Test
  void isParsablePlanJsonAllowsLineComments() {
    String json =
        """
        {
          "chain": { "name": "C", "description": "" },
          "elements": [ { "clientId": "a", "type": "http-trigger" } ],
          "connections": []
        }
        """;
    assertTrue(ChainImplementationPlanCapture.isParsablePlanJson(json));
  }

  @Test
  void collectFencedCodeBlocksPreservesDocumentOrder() {
    String markdown =
        """
        before
        ```
        first
        ```
        between
        ```json
        second
        ```
        """;
    List<String> blocks = ChainImplementationPlanCapture.collectFencedCodeBlocks(markdown);
    assertEquals(2, blocks.size());
    assertEquals("first", blocks.get(0));
    assertEquals("second", blocks.get(1));
  }
}
