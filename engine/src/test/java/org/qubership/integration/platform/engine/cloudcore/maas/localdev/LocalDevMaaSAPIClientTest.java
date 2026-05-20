package org.qubership.integration.platform.engine.cloudcore.maas.localdev;

import com.netcracker.cloud.maas.client.impl.dto.kafka.v1.conf.TopicCreateInteraction;
import com.netcracker.cloud.maas.client.impl.dto.rabbit.v1.conf.VHostCreateInteraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class LocalDevMaaSAPIClientTest {
    private LocalDevMaaSAPIClient localDevMaaSAPIClient;

    @BeforeEach
    void setUp() {
        this.localDevMaaSAPIClient = new LocalDevMaaSAPIClient(null, null);
    }

    @Test
    void testParseKafka() {
        String configResponse = """
                [
                  {
                    "request": {
                      "apiVersion": "nc.maas.kafka/v1",
                      "kind": "topic",
                      "pragma": {
                        "on-entity-exists": 1
                      },
                      "spec": {
                        "name": "dev.test.topic",
                        "classifier": {
                          "name": "test.topic",
                          "namespace": "dev"
                        },
                        "replicationFactor": "inherit",
                        "configs": {
                          "cleanup.policy": "delete",
                          "retention.ms": "604800000"
                        }
                      }
                    },
                    "result": {}
                  }
                ]
                """;

        var result = this.localDevMaaSAPIClient.deserializeConfigResponse(configResponse);
        assertEquals(1, result.size());
        assertInstanceOf(TopicCreateInteraction.class, result.get(0));
    }

    @Test
    void testParseRabbit() {
        String configResponse = """
                [
                  {
                    "request": {
                      "apiVersion": "nc.maas.rabbit/v1",
                      "kind": "vhost",
                      "spec": {
                        "classifier": {
                          "name": "public",
                          "namespace": "dev"
                        },
                        "entities": {
                          "queues": [
                            {
                              "auto_delete": false,
                              "durable": true,
                              "exclusive": false,
                              "name": "dev-test-queue"
                            }
                          ]
                        }
                      }
                    },
                    "result": {}
                  }
                ]
                """;

        var result = this.localDevMaaSAPIClient.deserializeConfigResponse(configResponse);
        assertEquals(1, result.size());
        assertInstanceOf(VHostCreateInteraction.class, result.get(0));
    }
}
