package org.qubership.integration.platform.ai.chat.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UploadedSpecTitleExtractorTest {

  @Test
  void resolveDisplayNameUsesTitleWhenAvailable() {
    byte[] content = "{\"info\":{\"title\":\"Stub OpenAPI Service\"}}".getBytes(StandardCharsets.UTF_8);

    String displayName =
        UploadedSpecTitleExtractor.resolveDisplayName("550db902-bbad-48a7-a044-cf0471829531.json", content);

    assertEquals("Stub OpenAPI Service", displayName);
  }

  @Test
  void resolveDisplayNameSanitizesTitle() {
    byte[] content = "{\"info\":{\"title\":\"  Order\\tAPI <beta>  \"}}".getBytes(StandardCharsets.UTF_8);

    String displayName = UploadedSpecTitleExtractor.resolveDisplayName("orders.json", content);

    assertEquals("Order API beta", displayName);
  }

  @Test
  void resolveDisplayNameFallsBackToBaseNameWhenTitleMissing() {
    byte[] content = "{\"info\":{\"version\":\"1.0\"}}".getBytes(StandardCharsets.UTF_8);

    String displayName = UploadedSpecTitleExtractor.resolveDisplayName("orders.json", content);

    assertEquals("orders", displayName);
  }

  @Test
  void resolveDisplayNameReturnsTitleEvenWhenItMatchesBaseName() {
    byte[] content = "{\"info\":{\"title\":\"orders\"}}".getBytes(StandardCharsets.UTF_8);

    String displayName = UploadedSpecTitleExtractor.resolveDisplayName("orders.json", content);

    assertEquals("orders", displayName);
  }

  @Test
  void resolveDisplayNameSanitizesTitleThatLooksLikeFilename() {
    byte[] content = "{\"info\":{\"title\":\"orders.json\"}}".getBytes(StandardCharsets.UTF_8);

    String displayName = UploadedSpecTitleExtractor.resolveDisplayName("orders.json", content);

    assertEquals("orders json", displayName);
  }

  @Test
  void resolveDisplayNameHandlesYamlContent() {
    byte[] content = "info:\n  title: Orders API\n".getBytes(StandardCharsets.UTF_8);

    String displayName = UploadedSpecTitleExtractor.resolveDisplayName("orders.yaml", content);

    assertEquals("Orders API", displayName);
  }

  @Test
  void resolveDisplayNameHandlesBlankTitle() {
    byte[] content = "{\"info\":{\"title\":\"   \"}}".getBytes(StandardCharsets.UTF_8);

    String displayName = UploadedSpecTitleExtractor.resolveDisplayName("orders.json", content);

    assertEquals("orders", displayName);
  }

  @Test
  void resolveSpecNameUsesSanitizedTitle() {
    byte[] content = "{\"info\":{\"title\":\"  Order\\tAPI  \"}}".getBytes(StandardCharsets.UTF_8);

    String specName = UploadedSpecTitleExtractor.resolveSpecName(content, "orders-api.yaml");

    assertEquals("Order API", specName);
  }

  @Test
  void resolveSpecNameFallsBackToBaseName() {
    byte[] content = "{}".getBytes(StandardCharsets.UTF_8);

    String specName = UploadedSpecTitleExtractor.resolveSpecName(content, "orders-api.yaml");

    assertEquals("orders-api", specName);
  }
}
