/**
 * `MaasActionsController`, the half of it this stack can prove.
 *
 * Measured against the running stack (`POST /v1/maas-actions/kafka` and
 * `POST /v1/maas-actions/rabbitmq` both answer 500, the first "Failed to create kafka topic in
 * MaaS", the second an I/O error resolving the host `maas-agent`): the catalog wires
 * `LocalDevMaaSAPIClient` because `maas.local-dev.enabled` is `${qip.standalone}`, which defaults
 * to `true` (`runtime-catalog/src/main/resources/application.yml`), against
 * `maas.agent.url: ${MAAS_AGENT_URL:}`. No compose env file sets `MAAS_AGENT_URL`, so the value is
 * empty and every call the client makes to `<agentUrl>/api/v1/config` fails before it leaves the
 * container -- `http://` with nothing after it, or once the scheme is stripped, a bare "maas-agent"
 * DNS lookup nothing in this stack answers. The engine side has the same gap
 * (`connectionSourceType: maas`); this is the same fact confirmed at the controller MaaS owns.
 *
 * The other two endpoints need no agent at all. `MaasService.getMaasDeclarativeFileKafka` and
 * `getMaasDeclarativeFileRabbitMq` build a `MaasConfig` object and hand it to `YAMLMapper` -- no
 * `kafkaMaaSClient`, no `rabbitMaaSClient`, no network call anywhere in the path -- so they are
 * covered here, and the agent-dependent two stay `not-covered` with that reason.
 *
 * `connectionSourceType: maas` on `kafka-trigger-2`, `kafka-sender-2`, `rabbitmq-trigger-2` and
 * `rabbitmq-sender-2`, and the `maasClassifierTenantEnabled` axis nested under it, stay
 * `not-covered` for the same reason: reaching either needs a live provisioning round trip this
 * stack cannot make, so there is no classifier-settings case to add. Per Post-Completion, "do not
 * substitute a stub that proves the element serialized its configuration correctly and nothing
 * else" -- a fixture that never deployed would only prove exactly that.
 */
import yaml from "js-yaml";
import { test, expect } from "../../support/fixtures.js";
import { callToken } from "../../support/run.js";

interface MaasKafkaManifest {
  apiVersion: string;
  kind: string;
  spec: { classifier: { name: string; namespace: string } };
}

interface MaasRabbitmqManifest {
  apiVersion: string;
  kind: string;
  spec: {
    classifier: { name: string; namespace: string };
    entities: {
      exchanges?: { name: string; type: string; durable: string; auto_delete: string }[];
      queues?: { name: string; durable: string; auto_delete: string }[];
      bindings?: { source: string; destination: string; routing_key?: string }[];
    };
  };
}

test(
  "POST /v1/maas-actions/kafka/declarative returns a nc.maas.kafka/v1 topic manifest",
  { tag: ["@catalog", "@tier2"] },
  async ({ catalog }) => {
    const classifier = callToken("maas-kafka");

    const response = await catalog.raw(
      "post",
      `/v1/maas-actions/kafka/declarative?topicClassifierName=${encodeURIComponent(classifier)}`,
    );
    expect(response.status()).toBe(200);
    expect(response.headers()["content-disposition"]).toContain("maas-configuration.yaml");

    const manifest = yaml.load(await response.text()) as MaasKafkaManifest;
    expect(manifest.apiVersion).toBe("nc.maas.kafka/v1");
    expect(manifest.kind).toBe("topic");
    expect(manifest.spec.classifier.name).toBe(classifier);
    // `MAAS_NAMESPACE_PLACEHOLDER` in `MaasService.java` -- the file is meant to be applied inside
    // the target namespace, which this endpoint (unlike the agent-backed one) never resolves itself.
    expect(manifest.spec.classifier.namespace).toBe("${ENV_NAMESPACE}");
  },
);

test(
  "POST /v1/maas-actions/rabbitmq/declarative answers 200 with the exchange, queue and binding",
  { tag: ["@catalog", "@tier2"] },
  async ({ catalog }) => {
    const vhost = callToken("maas-rmq-vhost");
    const exchange = callToken("maas-rmq-exchange");
    const queue = callToken("maas-rmq-queue");
    const routingKey = callToken("maas-rmq-rk");

    const response = await catalog.raw(
      "post",
      `/v1/maas-actions/rabbitmq/declarative?vhost=${encodeURIComponent(vhost)}` +
        `&exchange=${encodeURIComponent(exchange)}&queue=${encodeURIComponent(queue)}` +
        `&routingKey=${encodeURIComponent(routingKey)}`,
    );
    expect(response.status()).toBe(200);
    expect(response.headers()["content-disposition"]).toContain("maas-configuration.yaml");

    const manifest = yaml.load(await response.text()) as MaasRabbitmqManifest;
    expect(manifest.apiVersion).toBe("nc.maas.rabbit/v1");
    expect(manifest.kind).toBe("vhost");
    expect(manifest.spec.classifier.name).toBe(vhost);
    expect(manifest.spec.entities.exchanges).toEqual([
      { name: exchange, type: "direct", durable: "true", auto_delete: "false" },
    ]);
    expect(manifest.spec.entities.queues).toEqual([{ name: queue, durable: "true", auto_delete: "false" }]);
    expect(manifest.spec.entities.bindings).toEqual([
      { source: exchange, destination: queue, routing_key: routingKey },
    ]);
  },
);
