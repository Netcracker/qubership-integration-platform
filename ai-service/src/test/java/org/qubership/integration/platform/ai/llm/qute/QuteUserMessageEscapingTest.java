package org.qubership.integration.platform.ai.llm.qute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import io.quarkiverse.langchain4j.QuarkusPromptTemplateFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class QuteUserMessageEscapingTest {

  @Test
  void escapesBracesAndPreservesBackslashesForQute() {
    assertEquals("plain", QuteUserMessageEscaping.escapeForAiServiceUserMessage("plain"));
    assertEquals("\\{id}", QuteUserMessageEscaping.escapeForAiServiceUserMessage("{id}"));
    assertEquals(
        "\\".repeat(2) + "{", QuteUserMessageEscaping.escapeForAiServiceUserMessage("\\" + "{"));
  }

  @Test
  void preservesScriptTextAfterProductionTemplateRendering() {
    String text = "{\"script\":\"exchange.in.body = 'Hello'\\nreturn exchange.in.body\"}";
    var engine = Engine.builder().addDefaults()
        .addParserHook(new QuarkusPromptTemplateFactory.MustacheTemplateVariableStyleParserHook())
        .build();

    assertEquals(text, engine.parse(QuteUserMessageEscaping.escapeForAiServiceUserMessage(text)).render());
  }

  @Test
  void rendersSystemScriptExampleWithExecutableLineBreaks() throws Exception {
    try (var source = getClass().getClassLoader()
        .getResourceAsStream("prompts/script-body-repair-system.md")) {
      var engine = Engine.builder().addDefaults()
          .addParserHook(new QuarkusPromptTemplateFactory.MustacheTemplateVariableStyleParserHook())
          .build();
      String rendered = engine.parse(new String(source.readAllBytes(), StandardCharsets.UTF_8)).render();
      int exampleStart = rendered.indexOf('{', rendered.indexOf("Example tool argument"));
      var example = new ObjectMapper().readTree(rendered.substring(exampleStart));

      assertEquals("exchange.in.body = 'Hello'\nreturn exchange.in.body",
          example.get("scripts").get(0).get("script").asText());
    }
  }

  @Test
  void nullAndEmptyPassThrough() {
    assertNull(QuteUserMessageEscaping.escapeForAiServiceUserMessage(null));
    assertEquals("", QuteUserMessageEscaping.escapeForAiServiceUserMessage(""));
  }
}
