package org.qubership.integration.platform.ai.llm.qute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import io.quarkiverse.langchain4j.QuarkusPromptTemplateFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuteUserMessageEscapingTest {

  @Test
  void leavesTextWithoutDelimitersUnchanged() {
    assertEquals("plain", QuteUserMessageEscaping.escapeForAiServiceUserMessage("plain"));
    assertEquals("path\\value\\n", QuteUserMessageEscaping.escapeForAiServiceUserMessage("path\\value\\n"));
  }

  @Test
  void preservesScriptTextAfterProductionTemplateRendering() {
    String text = "{\"script\":\"exchange.in.body = 'Hello'\\nreturn exchange.in.body\"}";
    var engine = Engine.builder().addDefaults()
        .addParserHook(new QuarkusPromptTemplateFactory.MustacheTemplateVariableStyleParserHook())
        .build();

    assertEquals(text, engine.parse(QuteUserMessageEscaping.escapeForAiServiceUserMessage(text)).render());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"outer\":{\"inner\":\"text\"}}",
      "[{\"outer\":{\"inner\":{}}}]",
      "{{token}}",
      "{id}",
      "{|literal|}",
      "if (ready) { values.each { value -> print(value) }}",
      "\\{literal\\}\\}",
      "\\{",
      "before\n}}}\nafter"
  })
  void preservesNestedTextAfterProductionTemplateRendering(String text) {
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
