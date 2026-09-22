package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class CredentialPlaceholderApplicatorTest {

  @Test
  void replacesLiteralCredentialsOnEveryElementAndKeepsSecuredReferences() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("orders", "Orders"),
            List.of(
                new ChainPlanNode(
                    "mail",
                    "mail-sender",
                    "Mail",
                    null,
                    null,
                    List.of(
                        new PlanProperty("from", "sender@example.com"),
                        new PlanProperty("password", "abc"))),
                new ChainPlanNode(
                    "sftp",
                    "sftp-upload",
                    "SFTP",
                    null,
                    null,
                    List.of(new PlanProperty("password", "#{SFTP_PASSWORD}"))),
                new ChainPlanNode(
                    "rabbit",
                    "rabbitmq-trigger-2",
                    "Rabbit",
                    null,
                    null,
                    List.of(new PlanProperty("saslJaasConfig", "secret-material")))),
            List.of());

    ChainPlanGraph secured = CredentialPlaceholderApplicator.apply(graph);

    assertEquals("sender@example.com", value(secured, "mail", "from"));
    assertEquals("#{MAIL_SENDER_PASSWORD}", value(secured, "mail", "password"));
    assertEquals("#{SFTP_PASSWORD}", value(secured, "sftp", "password"));
    assertEquals("#{RABBITMQ_TRIGGER_2_SASL_JAAS_CONFIG}", value(secured, "rabbit", "saslJaasConfig"));
  }

  private static String value(ChainPlanGraph graph, String nodeId, String key) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .flatMap(node -> node.properties().stream())
        .filter(property -> key.equals(property.key()))
        .map(PlanProperty::value)
        .findFirst()
        .orElseThrow();
  }
}
