import yaml from "js-yaml";
import { externalSettings, type ExternalBrokers } from "../env/external-settings.js";
import { elementsOf, readFixtureDocument, type RenderedTree } from "./templating.js";

function kafkaAddress(address: unknown, kafka: NonNullable<ExternalBrokers["kafka"]>): unknown {
  switch (address) {
    case "kafka:29092": return kafka.engine;
    case "kafka:29094": return kafka.listeners?.saslPlaintext ?? address;
    case "kafka:29095": return kafka.listeners?.tls ?? address;
    case "kafka:29096": return kafka.listeners?.saslTls ?? address;
    case "kafka:29097": return kafka.listeners?.tls13 ?? address;
    default: return address;
  }
}

function rabbitmqConnection(rabbit: NonNullable<ExternalBrokers["rabbitmq"]>) {
  return {
    username: rabbit.username,
    password: rabbit.password,
    vhost: decodeURIComponent(new URL(rabbit.host).pathname.slice(1)) || "/",
  };
}

export function configureBrokerSpecification(source: string): string {
  const settings = externalSettings();
  if (!settings?.full || !settings.brokers) return source;

  const { kafka, rabbitmq } = settings.brokers;
  const api = yaml.load(source) as { servers?: Record<string, { url?: string; host?: string }> };
  let changed = false;
  for (const server of Object.values(api.servers ?? {})) {
    for (const field of ["url", "host"] as const) {
      const address = server[field];
      const configured = address === "kafka:29092" ? kafka?.engine : address === "rabbitmq:5672" ? rabbitmq?.engine : undefined;
      if (configured === undefined || configured === address) continue;
      server[field] = configured;
      changed = true;
    }
  }
  return changed ? yaml.dump(api, { lineWidth: -1 }) : source;
}

export function configureBrokerFixtures(fixtures: Array<[string, RenderedTree]>): Array<[string, RenderedTree]> {
  return fixtures.map(([name, tree]) => [name, configureBrokerFixture(name, tree)]);
}

export function configureBrokerFixture(name: string, tree: RenderedTree): RenderedTree {
  const settings = externalSettings();
  const brokers = settings?.brokers;
  if (!settings?.full || !brokers) return tree;

  const { kind, document, companions } = readFixtureDocument(name, tree);
  const { kafka, rabbitmq, pubsub, sftp } = brokers;
  if (kind === "chain") {
    for (const { type, properties } of elementsOf(document)) {
      if (!properties) continue;
      if (kafka && (type === "kafka-trigger-2" || type === "kafka-sender-2")) {
        properties.brokers = kafkaAddress(properties.brokers, kafka);
      }
      if (rabbitmq && (type === "rabbitmq-trigger-2" || type === "rabbitmq-sender-2")) {
        Object.assign(properties, { addresses: rabbitmq.engine, ...rabbitmqConnection(rabbitmq) });
      }
      if (pubsub?.projectId && (type === "pubsub-trigger" || type === "pubsub-sender")) {
        properties.projectId = pubsub.projectId;
      }
      if (sftp && typeof properties.connectUrl === "string" && properties.connectUrl.startsWith("sftp-server:22/")) {
        properties.connectUrl = sftp.engine + properties.connectUrl.slice("sftp-server:22".length);
        properties.username = sftp.username;
        properties.password = sftp.password;
      }
    }
  }

  if (kind === "service") {
    const { environments = [] } = document.content as {
      environments?: { address: string; properties: Record<string, unknown> }[];
    };
    for (const env of environments) {
      if (kafka && env.address === "kafka:29092") env.address = kafka.engine;
      if (rabbitmq && env.address === "rabbitmq:5672") {
        env.address = rabbitmq.engine;
        Object.assign(env.properties, rabbitmqConnection(rabbitmq));
      }
    }
    for (const [file, source] of companions) {
      if (!file.startsWith("resources/") || !file.endsWith(".yaml")) continue;
      companions.set(file, configureBrokerSpecification(source));
    }
  }

  const file = [...tree.keys()].find((entry) => !companions.has(entry))!;
  return new Map([[file, yaml.dump(document, { lineWidth: -1 })], ...companions]);
}
