import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import { Chain, DiagramMode, Element } from "../../api/apiTypes.ts";
import { buildSequenceDiagram } from "../../diagrams/builder.ts";

jest.mock("../../api/api.ts", () => ({
  api: {
    findChainByElementId: jest.fn().mockResolvedValue({
      id: "called-chain-id",
      name: "chain trigger",
    } as Partial<Chain>),
    getService: jest.fn().mockResolvedValue({
      id: "service-1",
      name: "TestService",
      activeEnvironmentId: "env-1",
    }),
    getEnvironments: jest.fn().mockResolvedValue([]),
  },
}));

function makeElement(overrides: Partial<Element>): Element {
  return {
    id: overrides.id ?? "element-1",
    name: overrides.name ?? "Test Element",
    chainId: "chain-1",
    type: overrides.type ?? "script",
    properties: overrides.properties ?? ({} as never),
    mandatoryChecksPassed: true,
    children: overrides.children,
    parentElementId: overrides.parentElementId,
  } as Element;
}

function makeChain(
  elements: Element[],
  dependencies: { from: string; to: string }[] = [],
): Chain {
  return {
    id: "chain-1",
    name: "Test Chain",
    elements,
    dependencies,
  } as unknown as Chain;
}

function findMessage(
  actions: unknown[],
  predicate: (msg: Record<string, unknown>) => boolean,
): Record<string, unknown> | undefined {
  for (const action of actions) {
    const a = action as Record<string, unknown>;
    if (a.type === "message" && predicate(a)) return a;
    if (a.actions) {
      const found = findMessage(a.actions as unknown[], predicate);
      if (found) return found;
    }
    if (a.branches) {
      for (const b of a.branches as Record<string, unknown>[]) {
        const found = findMessage(b.actions as unknown[], predicate);
        if (found) return found;
      }
    }
  }
  return undefined;
}

function collectMessages(actions: unknown[]): string[] {
  const messages: string[] = [];
  for (const action of actions) {
    const a = action as Record<string, unknown>;
    if (a.type === "message" && a.message) messages.push(a.message as string);
    if (a.actions) messages.push(...collectMessages(a.actions as unknown[]));
    if (a.branches) {
      for (const b of a.branches as Record<string, unknown>[]) {
        messages.push(...collectMessages(b.actions as unknown[]));
      }
    }
  }
  return messages;
}

describe("buildSequenceDiagram", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("HTTP Trigger", () => {
    it("should create diagram with HTTP trigger participant and request message", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: {
          contextPath: "/api/test",
          httpMethodRestrict: "GET,POST",
          externalRoute: true,
          privateRoute: false,
        } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      expect(diagram.autonumber).toBe(true);
      expect(diagram.participants.length).toBeGreaterThanOrEqual(2);
      const messages = collectMessages(diagram.actions);
      expect(messages.some((m) => m.includes("/api/test"))).toBe(true);
      expect(messages.some((m) => m.includes("GET,POST"))).toBe(true);
    });

    it("should show external route info in participant name", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: {
          contextPath: "/test",
          externalRoute: true,
          privateRoute: false,
        } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const extParticipant = diagram.participants.find((p) =>
        p.name?.includes("external"),
      );
      expect(extParticipant).toBeDefined();
    });
  });

  describe("Loop element", () => {
    it("should use expression as loop label when provided", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const loop = makeElement({
        id: "loop-1",
        name: "My Loop",
        type: "loop-2",
        properties: { expression: "item.count > 0" } as never,
      });
      const chain = makeChain(
        [trigger, loop],
        [{ from: "trigger-1", to: "loop-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const loopAction = diagram.actions
        .flatMap((a) => {
          const act = a as Record<string, unknown>;
          return act.actions ? (act.actions as unknown[]) : [a];
        })
        .find((a) => (a as Record<string, unknown>).type === "loop") as
        | Record<string, unknown>
        | undefined;
      expect(loopAction?.label).toBe("item.count > 0");
    });

    it("should fall back to element name when expression is missing", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const loop = makeElement({
        id: "loop-1",
        name: "My Loop",
        type: "loop-2",
        properties: { maxLoopIteration: 1500 } as never,
      });
      const chain = makeChain(
        [trigger, loop],
        [{ from: "trigger-1", to: "loop-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const loopAction = diagram.actions
        .flatMap((a) => {
          const act = a as Record<string, unknown>;
          return act.actions ? (act.actions as unknown[]) : [a];
        })
        .find((a) => (a as Record<string, unknown>).type === "loop") as
        | Record<string, unknown>
        | undefined;
      expect(loopAction?.label).toBe("My Loop");
    });
  });

  describe("HTTP Sender", () => {
    it("should show method and path for URI with path", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sender = makeElement({
        id: "sender-1",
        name: "HTTP Sender",
        type: "http-sender",
        properties: {
          httpMethod: "POST",
          uri: "http://example.com/api/v1/users",
          isExternalCall: true,
        } as never,
      });
      const chain = makeChain(
        [trigger, sender],
        [{ from: "trigger-1", to: "sender-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) => m.includes("POST") && m.includes("/api/v1/users")),
      ).toBe(true);
    });

    it("should show full URI when no path component exists", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sender = makeElement({
        id: "sender-1",
        name: "HTTP Sender",
        type: "http-sender",
        properties: {
          httpMethod: "GET",
          uri: "http://test.com",
          isExternalCall: true,
        } as never,
      });
      const chain = makeChain(
        [trigger, sender],
        [{ from: "trigger-1", to: "sender-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) => m.includes("GET") && m.includes("http://test.com"),
        ),
      ).toBe(true);
    });
  });

  describe("GraphQL Sender", () => {
    it("should show operation name when provided", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sender = makeElement({
        id: "gql-1",
        name: "GraphQL Sender",
        type: "graphql-sender",
        properties: {
          uri: "https://example.com/graphql",
          operationName: "GetUsers",
        } as never,
      });
      const chain = makeChain(
        [trigger, sender],
        [{ from: "trigger-1", to: "gql-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(messages.some((m) => m.includes("operation: GetUsers"))).toBe(
        true,
      );
    });

    it("should show URI when operation name is not provided", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sender = makeElement({
        id: "gql-1",
        name: "GraphQL Sender",
        type: "graphql-sender",
        properties: {
          uri: "https://example.com/graphql",
        } as never,
      });
      const chain = makeChain(
        [trigger, sender],
        [{ from: "trigger-1", to: "gql-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) => m.includes("https://example.com/graphql")),
      ).toBe(true);
      expect(messages.every((m) => !m.includes("%empty_property%"))).toBe(true);
    });
  });

  describe("PubSub Sender", () => {
    it("should not duplicate destination name in message", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sender = makeElement({
        id: "pubsub-1",
        name: "PubSub Sender",
        type: "pubsub-sender",
        properties: {
          projectId: "my-project",
          destinationName: "my-topic",
        } as never,
      });
      const chain = makeChain(
        [trigger, sender],
        [{ from: "trigger-1", to: "pubsub-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      const pubsubMsg = messages.find((m) => m.includes("my-topic"));
      expect(pubsubMsg).toBeDefined();
      // Should contain "my-topic" exactly once (not duplicated)
      const count = (pubsubMsg!.match(/my-topic/g) ?? []).length;
      expect(count).toBe(1);
    });
  });

  describe("RabbitMQ Sender", () => {
    it("should show exchange in message", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sender = makeElement({
        id: "rmq-1",
        name: "RabbitMQ Sender",
        type: "rabbitmq-sender-2",
        properties: {
          addresses: "localhost:5672",
          exchange: "my-exchange",
          connectionSourceType: "manual",
        } as never,
      });
      const chain = makeChain(
        [trigger, sender],
        [{ from: "trigger-1", to: "rmq-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) => m.includes("exchange") && m.includes("my-exchange"),
        ),
      ).toBe(true);
    });
  });

  describe("Kafka Sender", () => {
    it("should show topic in message for manual connection", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sender = makeElement({
        id: "kafka-1",
        name: "Kafka Sender",
        type: "kafka-sender-2",
        properties: {
          brokers: "localhost:9092",
          topics: "my-topic",
          connectionSourceType: "manual",
        } as never,
      });
      const chain = makeChain(
        [trigger, sender],
        [{ from: "trigger-1", to: "kafka-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) => m.includes("topic") && m.includes("my-topic")),
      ).toBe(true);
    });

    it("should show classifier for maas connection", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sender = makeElement({
        id: "kafka-1",
        name: "Kafka Sender",
        type: "kafka-sender-2",
        properties: {
          brokers: "localhost:9092",
          topicsClassifierName: "my-classifier",
          connectionSourceType: "maas",
        } as never,
      });
      const chain = makeChain(
        [trigger, sender],
        [{ from: "trigger-1", to: "kafka-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(messages.some((m) => m.includes("my-classifier"))).toBe(true);
    });
  });

  describe("Chain Call", () => {
    it("should create chain call message with activate/deactivate", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const chainCall = makeElement({
        id: "cc-1",
        name: "Chain Call",
        type: "chain-call-2",
        properties: {
          elementId: "40625b0d-b5fc-47fb-a668-fc2f816b42dc",
        } as never,
      });
      const chain = makeChain(
        [trigger, chainCall],
        [{ from: "trigger-1", to: "cc-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(messages.some((m) => m.includes("chain trigger call"))).toBe(true);
      expect(messages.some((m) => m === "Response")).toBe(true);
    });
  });

  describe("File operations", () => {
    it("should show file name for file-write", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const fileWrite = makeElement({
        id: "fw-1",
        name: "File Write",
        type: "file-write",
        properties: { fileName: "output.txt" } as never,
      });
      const chain = makeChain(
        [trigger, fileWrite],
        [{ from: "trigger-1", to: "fw-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) => m.includes("Write local file") && m.includes("output.txt"),
        ),
      ).toBe(true);
    });

    it("should show file name for file-read", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const fileRead = makeElement({
        id: "fr-1",
        name: "File Read",
        type: "file-read",
        properties: { fileName: "input.csv" } as never,
      });
      const chain = makeChain(
        [trigger, fileRead],
        [{ from: "trigger-1", to: "fr-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) => m.includes("Read local file") && m.includes("input.csv"),
        ),
      ).toBe(true);
    });
  });

  describe("Mail Sender", () => {
    it("should show from and to in message", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const mail = makeElement({
        id: "mail-1",
        name: "Mail Sender",
        type: "mail-sender",
        properties: {
          url: "smtp://mail.example.com",
          from: "a@example.com",
          to: "b@example.com",
        } as never,
      });
      const chain = makeChain(
        [trigger, mail],
        [{ from: "trigger-1", to: "mail-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) => m.includes("a@example.com") && m.includes("b@example.com"),
        ),
      ).toBe(true);
    });
  });

  describe("Condition element", () => {
    it("should create alternatives with branches", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const condition = makeElement({
        id: "cond-1",
        name: "Condition",
        type: "condition",
        children: [
          makeElement({
            id: "if-1",
            name: "If",
            type: "if",
            parentElementId: "cond-1",
            properties: { condition: "x > 0" } as never,
          }),
          makeElement({
            id: "else-1",
            name: "Else",
            type: "else",
            parentElementId: "cond-1",
          }),
        ],
      });
      const chain = makeChain(
        [trigger, condition],
        [{ from: "trigger-1", to: "cond-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      // Find an alternatives action in the diagram
      const findAlternatives = (
        actions: unknown[],
      ): Record<string, unknown> | undefined => {
        for (const a of actions) {
          const act = a as Record<string, unknown>;
          if (act.type === "alternatives") return act;
          if (act.actions) {
            const found = findAlternatives(act.actions as unknown[]);
            if (found) return found;
          }
        }
        return undefined;
      };
      const alt = findAlternatives(diagram.actions);
      expect(alt).toBeDefined();
      const branches = alt!.branches as Record<string, unknown>[];
      expect(branches.length).toBe(2);
      expect(branches[0].label as string).toContain("on condition x > 0");
    });
  });

  describe("Simple mode filtering", () => {
    it("should filter out internal messages in SIMPLE mode", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      // default element (script) generates self-message in FULL mode
      const script = makeElement({
        id: "script-1",
        name: "Script",
        type: "script",
        properties: { script: "//test" } as never,
      });
      const chain = makeChain(
        [trigger, script],
        [{ from: "trigger-1", to: "script-1" }],
      );

      const fullDiagram = await buildSequenceDiagram(chain, DiagramMode.FULL);
      const simpleDiagram = await buildSequenceDiagram(
        chain,
        DiagramMode.SIMPLE,
      );

      const fullMessages = collectMessages(fullDiagram.actions);
      const simpleMessages = collectMessages(simpleDiagram.actions);
      // In FULL mode, script generates a self-message
      expect(fullMessages.some((m) => m === "Script")).toBe(true);
      // In SIMPLE mode, internal self-messages are filtered
      expect(simpleMessages.every((m) => m !== "Script")).toBe(true);
    });
  });

  describe("Reuse Reference", () => {
    it("should resolve reuse reference to original element children", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const reuseChild = makeElement({
        id: "child-1",
        name: "Inner Script",
        type: "script",
        parentElementId: "reuse-1",
        properties: { script: "//inner" } as never,
      });
      const reuse = makeElement({
        id: "reuse-1",
        name: "Reuse",
        type: "reuse",
        children: [reuseChild],
      });
      const reuseRef = makeElement({
        id: "ref-1",
        name: "Reuse Reference",
        type: "reuse-reference",
        properties: { reuseElementId: "reuse-1" } as never,
      });
      const chain = makeChain(
        [trigger, reuse, reuseChild, reuseRef],
        [{ from: "trigger-1", to: "ref-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(messages.some((m) => m === "Inner Script")).toBe(true);
    });
  });

  describe("Participants deduplication", () => {
    it("should not create duplicate participants", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test", externalRoute: true } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const ids = diagram.participants.map((p) => p.id);
      const uniqueIds = new Set(ids);
      expect(ids.length).toBe(uniqueIds.size);
    });
  });

  describe("Scheduler trigger", () => {
    it("should show cron expression", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "Scheduler",
        type: "scheduler",
        properties: { "scheduler.cron": "0 0 * * *" } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) => m.includes("cron") && m.includes("0 0 * * *")),
      ).toBe(true);
    });
  });

  describe("Split element", () => {
    it("should create parallel branches and waiting message", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const splitChild1 = makeElement({
        id: "sc-1",
        name: "Main Split Element",
        type: "main-split-element-2",
        parentElementId: "split-1",
      });
      const splitChild2 = makeElement({
        id: "sc-2",
        name: "Split Element",
        type: "split-element-2",
        parentElementId: "split-1",
      });
      const split = makeElement({
        id: "split-1",
        name: "Split",
        type: "split-2",
        properties: {
          aggregationStrategy: "chainsAggregationStrategy",
        } as never,
        children: [splitChild1, splitChild2],
      });
      const chain = makeChain(
        [trigger, split, splitChild1, splitChild2],
        [{ from: "trigger-1", to: "split-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) =>
          m.includes("Waiting for 'split elements' to complete"),
        ),
      ).toBe(true);
    });
  });

  describe("SFTP elements", () => {
    it("should show download file message for sftp-download", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sftp = makeElement({
        id: "sftp-1",
        name: "SFTP Download",
        type: "sftp-download",
        properties: {
          connectUrl: "sftp://host",
          fileName: "data.csv",
        } as never,
      });
      const chain = makeChain(
        [trigger, sftp],
        [{ from: "trigger-1", to: "sftp-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) => m.includes("Download file") && m.includes("data.csv"),
        ),
      ).toBe(true);
    });

    it("should show upload file message for sftp-upload", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sftp = makeElement({
        id: "sftp-1",
        name: "SFTP Upload",
        type: "sftp-upload",
        properties: {
          connectUrl: "sftp://host",
          fileName: "upload.zip",
        } as never,
      });
      const chain = makeChain(
        [trigger, sftp],
        [{ from: "trigger-1", to: "sftp-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) => m.includes("Upload file") && m.includes("upload.zip"),
        ),
      ).toBe(true);
    });

    it("should create SFTP participant with connect URL", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sftp = makeElement({
        id: "sftp-1",
        name: "SFTP Download",
        type: "sftp-download",
        properties: {
          connectUrl: "sftp://myhost.com",
          fileName: "f.txt",
        } as never,
      });
      const chain = makeChain(
        [trigger, sftp],
        [{ from: "trigger-1", to: "sftp-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      expect(
        diagram.participants.some((p) => p.name?.includes("sftp://myhost.com")),
      ).toBe(true);
    });
  });

  describe("SFTP Trigger", () => {
    it("should create group with download message", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "SFTP Trigger",
        type: "sftp-trigger-2",
        properties: { connectUrl: "sftp://host", natInclude: "*.csv" } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) =>
            m.includes("Download created/updated file") && m.includes("*.csv"),
        ),
      ).toBe(true);
    });
  });

  describe("Chain Trigger", () => {
    it("should create group with chain call message", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "Chain Trigger",
        type: "chain-trigger-2",
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(messages.some((m) => m.includes("chain call"))).toBe(true);
      expect(messages.some((m) => m === "Response")).toBe(true);
    });
  });

  describe("Context Storage", () => {
    it("should show GET operation with context ID", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const cs = makeElement({
        id: "cs-1",
        name: "Context Storage",
        type: "context-storage",
        properties: {
          contextServiceId: "ctx-svc-1",
          operation: "GET",
          contextId: "my-ctx",
          useCorrelationId: false,
        } as never,
      });
      const chain = makeChain(
        [trigger, cs],
        [{ from: "trigger-1", to: "cs-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) => m.includes("GET context") && m.includes("my-ctx")),
      ).toBe(true);
      expect(
        diagram.participants.some((p) => p.name?.includes("ctx-svc-1")),
      ).toBe(true);
    });

    it("should show correlation ID when enabled", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const cs = makeElement({
        id: "cs-1",
        name: "Context Storage",
        type: "context-storage",
        properties: {
          contextServiceId: "ctx-svc-1",
          operation: "PUT",
          useCorrelationId: "true",
        } as never,
      });
      const chain = makeChain(
        [trigger, cs],
        [{ from: "trigger-1", to: "cs-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(messages.some((m) => m.includes("use correlation ID"))).toBe(true);
    });
  });

  describe("Checkpoint", () => {
    it("should create group with trigger and checkpoint alternatives", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const checkpoint = makeElement({
        id: "cp-1",
        name: "Checkpoint",
        type: "checkpoint",
        properties: { checkpointElementId: "cp-elem-1" } as never,
      });
      const chain = makeChain(
        [trigger, checkpoint],
        [{ from: "trigger-1", to: "cp-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(messages.some((m) => m.includes("Save context"))).toBe(true);
      expect(messages.some((m) => m.includes("Load context"))).toBe(true);
      expect(messages.some((m) => m.includes("retry session"))).toBe(true);
    });
  });

  describe("Kafka Trigger", () => {
    it("should show topics for manual connection", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "Kafka Trigger",
        type: "kafka-trigger-2",
        properties: {
          brokers: "localhost:9092",
          topics: "my-topic",
          connectionSourceType: "manual",
          idempotency: { enabled: false },
        } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) => m.includes("topic") && m.includes("my-topic")),
      ).toBe(true);
    });

    it("should show classifier for maas connection", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "Kafka Trigger",
        type: "kafka-trigger-2",
        properties: {
          brokers: "localhost:9092",
          topicsClassifierName: "my-cls",
          connectionSourceType: "maas",
          idempotency: { enabled: false },
        } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) => m.includes("classifier") && m.includes("my-cls")),
      ).toBe(true);
    });
  });

  describe("RabbitMQ Trigger", () => {
    it("should show exchange and queues for manual connection", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "RabbitMQ Trigger",
        type: "rabbitmq-trigger-2",
        properties: {
          addresses: "localhost:5672",
          exchange: "my-exchange",
          queues: "my-queue",
          connectionSourceType: "manual",
          idempotency: { enabled: false },
        } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some((m) => m.includes("exchange") && m.includes("my-queue")),
      ).toBe(true);
    });

    it("should show classifier for non-manual connection", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "RabbitMQ Trigger",
        type: "rabbitmq-trigger-2",
        properties: {
          addresses: "localhost:5672",
          vhostClassifierName: "my-vhost-cls",
          connectionSourceType: "maas",
          idempotency: { enabled: false },
        } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const messages = collectMessages(diagram.actions);
      expect(
        messages.some(
          (m) => m.includes("classifier") && m.includes("my-vhost-cls"),
        ),
      ).toBe(true);
    });
  });

  describe("Choice element", () => {
    it("should create alternatives with when branches", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const choice = makeElement({
        id: "choice-1",
        name: "Choice",
        type: "choice",
        children: [
          makeElement({
            id: "when-1",
            name: "When A",
            type: "when",
            parentElementId: "choice-1",
            properties: { condition: "a == 1" } as never,
          }),
          makeElement({
            id: "otherwise-1",
            name: "Otherwise",
            type: "otherwise",
            parentElementId: "choice-1",
          }),
        ],
      });
      const chain = makeChain(
        [trigger, choice],
        [{ from: "trigger-1", to: "choice-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const findAlternatives = (
        actions: unknown[],
      ): Record<string, unknown> | undefined => {
        for (const a of actions) {
          const act = a as Record<string, unknown>;
          if (act.type === "alternatives") return act;
          if (act.actions) {
            const found = findAlternatives(act.actions as unknown[]);
            if (found) return found;
          }
        }
        return undefined;
      };
      const alt = findAlternatives(diagram.actions);
      expect(alt).toBeDefined();
      const branches = alt!.branches as Record<string, unknown>[];
      expect(
        branches.some((b) =>
          (b.label as string).includes("on condition a == 1"),
        ),
      ).toBe(true);
    });
  });

  describe("Try-Catch-Finally", () => {
    it("should create alternatives with catch branches", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const tcf = makeElement({
        id: "tcf-1",
        name: "Try Catch Finally",
        type: "try-catch-finally-2",
        children: [
          makeElement({
            id: "try-1",
            name: "Try",
            type: "try",
            parentElementId: "tcf-1",
          }),
          makeElement({
            id: "catch-1",
            name: "Catch",
            type: "catch",
            parentElementId: "tcf-1",
            properties: { exception: "java.lang.Exception" } as never,
          }),
          makeElement({
            id: "finally-1",
            name: "Finally",
            type: "finally",
            parentElementId: "tcf-1",
          }),
        ],
      });
      const chain = makeChain(
        [trigger, tcf],
        [{ from: "trigger-1", to: "tcf-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const findAlternatives = (
        actions: unknown[],
      ): Record<string, unknown> | undefined => {
        for (const a of actions) {
          const act = a as Record<string, unknown>;
          if (act.type === "alternatives") return act;
          if (act.actions) {
            const found = findAlternatives(act.actions as unknown[]);
            if (found) return found;
          }
        }
        return undefined;
      };
      const alt = findAlternatives(diagram.actions);
      expect(alt).toBeDefined();
      const branches = alt!.branches as Record<string, unknown>[];
      expect(
        branches.some((b) =>
          (b.label as string).includes("on exception java.lang.Exception"),
        ),
      ).toBe(true);
    });
  });

  describe("Split Async", () => {
    it("should create parallel branches", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const asyncChild1 = makeElement({
        id: "ac-1",
        name: "Async Split Element",
        type: "async-split-element-2",
        parentElementId: "sa-1",
      });
      const asyncChild2 = makeElement({
        id: "ac-2",
        name: "Async Split Element",
        type: "async-split-element-2",
        parentElementId: "sa-1",
      });
      const splitAsync = makeElement({
        id: "sa-1",
        name: "Split Async",
        type: "split-async-2",
        children: [asyncChild1, asyncChild2],
      });
      const chain = makeChain(
        [trigger, splitAsync, asyncChild1, asyncChild2],
        [{ from: "trigger-1", to: "sa-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const findParallel = (
        actions: unknown[],
      ): Record<string, unknown> | undefined => {
        for (const a of actions) {
          const act = a as Record<string, unknown>;
          if (act.type === "parallel") return act;
          if (act.actions) {
            const found = findParallel(act.actions as unknown[]);
            if (found) return found;
          }
        }
        return undefined;
      };
      const par = findParallel(diagram.actions);
      expect(par).toBeDefined();
      expect((par!.branches as unknown[]).length).toBe(2);
    });
  });

  describe("Service Call with integrationSystemId", () => {
    it("should create service participant", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const sc = makeElement({
        id: "sc-1",
        name: "Service Call",
        type: "service-call",
        properties: {
          integrationSystemId: "service-1",
          integrationOperationProtocolType: "http",
          integrationOperationMethod: "GET",
          integrationOperationPath: "/api/users",
          before: { type: "none" },
          after: [],
        } as never,
      });
      const chain = makeChain(
        [trigger, sc],
        [{ from: "trigger-1", to: "sc-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      expect(
        diagram.participants.some((p) => p.name?.includes("TestService")),
      ).toBe(true);
    });
  });

  describe("HTTP Trigger with service system type", () => {
    it("should show service name for system-type trigger", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: {
          contextPath: "/test",
          systemType: "EXTERNAL",
          integrationSystemId: "service-1",
          integrationOperationPath: "/api/users",
        } as never,
      });
      const chain = makeChain([trigger]);

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      expect(
        diagram.participants.some((p) => p.name?.includes("TestService")),
      ).toBe(true);
    });
  });

  describe("Simple mode with nested structures", () => {
    it("should filter empty alternatives in SIMPLE mode", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      // Condition with only internal (self) messages in branches
      const condition = makeElement({
        id: "cond-1",
        name: "Condition",
        type: "condition",
        children: [
          makeElement({
            id: "if-1",
            name: "If",
            type: "if",
            parentElementId: "cond-1",
            properties: { condition: "true" } as never,
            children: [
              makeElement({
                id: "script-in-if",
                name: "InternalScript",
                type: "script",
                parentElementId: "if-1",
              }),
            ],
          }),
        ],
      });
      const chain = makeChain(
        [trigger, condition],
        [{ from: "trigger-1", to: "cond-1" }],
      );

      const simpleDiagram = await buildSequenceDiagram(
        chain,
        DiagramMode.SIMPLE,
      );

      // Internal-only alternatives should be filtered out
      const findAlternatives = (
        actions: unknown[],
      ): Record<string, unknown> | undefined => {
        for (const a of actions) {
          const act = a as Record<string, unknown>;
          if (act.type === "alternatives") return act;
          if (act.actions) {
            const found = findAlternatives(act.actions as unknown[]);
            if (found) return found;
          }
        }
        return undefined;
      };
      // Either alternatives is filtered out entirely, or its branches have no internal messages
      const alt = findAlternatives(simpleDiagram.actions);
      if (alt) {
        const branches = alt.branches as Record<string, unknown>[];
        for (const branch of branches) {
          const msgs = collectMessages(branch.actions as unknown[]);
          expect(msgs.every((m) => m !== "InternalScript")).toBe(true);
        }
      }
    });
  });

  describe("Circuit Breaker", () => {
    it("should create alternatives branches for circuit breaker", async () => {
      const trigger = makeElement({
        id: "trigger-1",
        name: "HTTP Trigger",
        type: "http-trigger",
        properties: { contextPath: "/test" } as never,
      });
      const cbConfig = makeElement({
        id: "cb-config-1",
        name: "Circuit Breaker Configuration",
        type: "circuit-breaker-configuration-2",
        parentElementId: "cb-1",
        properties: { failureRateThreshold: 50 } as never,
      });
      const onFallback = makeElement({
        id: "fb-1",
        name: "On fallback",
        type: "on-fallback-2",
        parentElementId: "cb-1",
      });
      const cb = makeElement({
        id: "cb-1",
        name: "Circuit Breaker",
        type: "circuit-breaker-2",
        children: [cbConfig, onFallback],
      });
      const chain = makeChain(
        [trigger, cb, cbConfig, onFallback],
        [{ from: "trigger-1", to: "cb-1" }],
      );

      const diagram = await buildSequenceDiagram(chain, DiagramMode.FULL);

      const findAlternatives = (
        actions: unknown[],
      ): Record<string, unknown> | undefined => {
        for (const a of actions) {
          const act = a as Record<string, unknown>;
          if (act.type === "alternatives") return act;
          if (act.actions) {
            const found = findAlternatives(act.actions as unknown[]);
            if (found) return found;
          }
        }
        return undefined;
      };
      const alt = findAlternatives(diagram.actions);
      expect(alt).toBeDefined();
      const branches = alt!.branches as Record<string, unknown>[];
      expect(branches.some((b) => (b.label as string).includes("50%"))).toBe(
        true,
      );
    });
  });
});
