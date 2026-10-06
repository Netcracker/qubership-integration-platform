package org.qubership.integration.platform.ai.chain.folder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkiverse.langchain4j.runtime.QuarkusServiceOutputParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ChainListCommandTest {

  private final QuarkusServiceOutputParser parser = new QuarkusServiceOutputParser();

  @Test
  void acceptsAbsentGroupingAndRejectsUnknownValues() {
    // A plain-list response captured from Luna failed before reaching the scenario.
    ChainListCommand command = parse("""
        {
          "action": "FIND_CHAINS",
          "folder": "",
          "newName": "",
          "targetFolder": "",
          "chainIds": [],
          "chainNames": [],
          "filters": [{"field": "NAME", "condition": "STARTS_WITH", "value": "SF EVAL-LUNA-1005-R1"}],
          "question": "",
          "description": "",
          "addLabels": [],
          "removeLabels": [],
          "fromList": false,
          "positions": [],
          "excludePositions": [],
          "groupBy": ""
        }
        """);
    assertEquals(ChainListAction.FIND_CHAINS, command.action());
    assertEquals("SF EVAL-LUNA-1005-R1", command.filters().getFirst().value());
    assertNull(command.groupBy());

    for (String absent : new String[] {"", ",\"groupBy\":null", ",\"groupBy\":\"  \""}) {
      assertNull(parse("{\"action\":\"FIND_CHAINS\"" + absent + "}").groupBy());
    }
    for (ChainListCommand.Grouping grouping : ChainListCommand.Grouping.values()) {
      assertEquals(grouping, parse(withGrouping("\"" + grouping.name() + "\"")).groupBy());
    }
    for (String invalid : new String[] {"\"UNKNOWN\"", "\"folder\"", "1", "{}", "[]", "true"}) {
      assertThrows(RuntimeException.class, () -> parse(withGrouping(invalid)));
    }
    assertThrows(RuntimeException.class, () -> parse("{\"action\":\"\",\"groupBy\":\"\"}"));
  }

  private ChainListCommand parse(String json) {
    return (ChainListCommand) parser.parseText(ChainListCommand.class, json);
  }

  private static String withGrouping(String value) {
    return "{\"action\":\"FIND_CHAINS\",\"groupBy\":" + value + "}";
  }
}
