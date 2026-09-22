package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChainElementFamiliesBindingPolicyTest {

  private static final Set<String> IN_SCOPE_DIRECT =
      Set.of(
          "chain-trigger-2",
          "jms-trigger",
          "kafka-trigger-2",
          "mcp-trigger",
          "pubsub-trigger",
          "quartz-scheduler",
          "rabbitmq-trigger-2",
          "sds-trigger",
          "sftp-trigger-2",
          "graphql-sender",
          "http-sender",
          "jms-sender",
          "kafka-sender-2",
          "mail-sender",
          "pubsub-sender",
          "rabbitmq-sender-2",
          "scs-sender");

  @ParameterizedTest
  @CsvSource({
      "http-trigger, HTTP_TRIGGER_DUAL_MODE",
      "async-api-trigger, CATALOG_REQUIRED",
      "mcp-trigger, DIRECT",
      "chain-trigger-2, DIRECT",
      "http-sender, DIRECT",
      "kafka-sender-2, DIRECT",
      "mail-sender, DIRECT",
      "sftp-upload, DIRECT",
      "sftp-download, DIRECT",
      "scs-sender, DIRECT"
  })
  void classifiesKnownTypes(String type, ChainElementFamilies.BindingMode mode) {
    assertEquals(mode, ChainElementFamilies.bindingMode(type));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "chain-trigger-2", "jms-trigger", "kafka-trigger-2", "pubsub-trigger", "quartz-scheduler",
      "rabbitmq-trigger-2", "sds-trigger", "sftp-trigger-2", "graphql-sender", "http-sender",
      "jms-sender", "kafka-sender-2", "mail-sender", "pubsub-sender", "rabbitmq-sender-2",
      "scs-sender"
  })
  void directTypesNeverRequireCatalogByTypeAlone(String type) {
    assertEquals(ChainElementFamilies.BindingMode.DIRECT, ChainElementFamilies.bindingMode(type));
  }

  @Test
  void everyNonDeprecatedTriggerAndSenderInTheIndexIsClassified() {
    ChainElementCatalog catalog = new ChainElementCatalog(new ObjectMapper());
    Set<String> expected = new TreeSet<>();
    for (String type : catalog.allTypes()) {
      if (catalog.isDeprecated(type)) {
        continue;
      }
      if (ChainElementFamilies.isTrigger(type) || looksLikeSender(type)) {
        expected.add(type);
      }
    }
    assertEquals(expected, new TreeSet<>(ChainElementFamilies.classifiedTriggerAndSenderTypes()));
    assertTrue(expected.contains("mcp-trigger"));
    assertTrue(IN_SCOPE_DIRECT.contains("mcp-trigger"));
  }

  @Test
  void classifiedTriggersMatchFamilyTriggers() {
    Set<String> classifiedTriggers =
        ChainElementFamilies.classifiedTriggerAndSenderTypes().stream()
            .filter(ChainElementFamilies::isTrigger)
            .collect(Collectors.toUnmodifiableSet());
    assertEquals(ChainElementFamilies.TRIGGERS, classifiedTriggers);
  }

  private static boolean looksLikeSender(String type) {
    return type.endsWith("-sender") || type.endsWith("-sender-2");
  }
}
