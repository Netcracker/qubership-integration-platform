import {
  Chain,
  DiagramMode,
  Element,
  Environment,
  EnvironmentSourceType,
  IntegrationSystem,
} from "../api/apiTypes.ts";
import {
  Action,
  Branch,
  Message,
  Participant,
  SequenceDiagram,
} from "./model.ts";
import { api } from "../api/api.ts";

const EMPTY_PROPERTY_STUB = "%empty_property%";
const DEFAULT_RESPONSE_TITLE = "Response";

const TRIGGERS = [
  "async-api-trigger",
  "chain-trigger",
  "chain-trigger-2",
  "http-trigger",
  "kafka",
  "kafka-trigger-2",
  "pubsub-trigger",
  "quartz-scheduler",
  "rabbitmq",
  "rabbitmq-trigger-2",
  "scheduler",
  "sftp-trigger",
  "sftp-trigger-2",
];

const PARTICIPANTS_GETTERS: Record<string, ParticipantsGetter> = {
  "async-api-trigger": getAsyncApiTriggerParticipants,
  "chain-call": getChainCallParticipants,
  "chain-call-2": getChainCallParticipants,
  "chain-trigger": getChainTriggerParticipants,
  "chain-trigger-2": getChainTriggerParticipants,
  checkpoint: getCheckpointParticipants,
  "context-storage": getContextStorageParticipants,
  "graphql-sender": getGraphQlSenderParticipants,
  "http-trigger": getHttpTriggerParticipants,
  "http-sender": getHttpSenderParticipants,
  kafka: getKafkaParticipants,
  "kafka-sender": getKafkaParticipants,
  "kafka-sender-2": getKafkaParticipants,
  "kafka-trigger-2": getKafkaParticipants,
  "mail-sender": getMailParticipants,
  "pubsub-sender": getPubSubParticipants,
  "pubsub-trigger": getPubSubParticipants,
  rabbitmq: getRabbitMqParticipants,
  "rabbitmq-sender": getRabbitMqParticipants,
  "rabbitmq-sender-2": getRabbitMqParticipants,
  "rabbitmq-trigger-2": getRabbitMqParticipants,
  "service-call": getServiceCallParticipants,
  "sftp-download": getSftpParticipants,
  "sftp-trigger": getSftpParticipants,
  "sftp-trigger-2": getSftpParticipants,
  "sftp-upload": getSftpParticipants,
};

const ELEMENT_ACTIONS_GETTERS: Record<string, TriggerActionsGetter> = {
  "async-api-trigger": getAsyncApiTriggerActions,
  "chain-call": getChainCallActions,
  "chain-call-2": getChainCallActions,
  "chain-trigger": getChainTriggerActions,
  "chain-trigger-2": getChainTriggerActions,
  checkpoint: getCheckpointActions,
  choice: getChoiceActions,
  "circuit-breaker": getCircuitBreakerActions,
  "circuit-breaker-2": getCircuitBreakerActions,
  condition: getConditionActions,
  "context-storage": getContextStorageActions,
  "file-read": getFileReadActions,
  "file-write": getFileWriteActions,
  "graphql-sender": getGraphQlSenderActions,
  "http-sender": getHttpSenderActions,
  "http-trigger": getHttpTriggerActions,
  kafka: getKafkaTriggerActions,
  "kafka-sender": getKafkaSenderActions,
  "kafka-sender-2": getKafkaSenderActions,
  "kafka-trigger-2": getKafkaTriggerActions,
  loop: getLoopActions,
  "loop-2": getLoopActions,
  "mail-sender": getMailSenderActions,
  "pubsub-sender": getPubSubSenderActions,
  "pubsub-trigger": getPubSubTriggerActions,
  "quartz-scheduler": getSchedulerActions,
  rabbitmq: getRabbitMqTriggerActions,
  "rabbitmq-sender": getRabbitMqSenderActions,
  "rabbitmq-sender-2": getRabbitMqSenderActions,
  "rabbitmq-trigger-2": getRabbitMqTriggerActions,
  "reuse-reference": getReuseReferenceActions,
  scheduler: getSchedulerActions,
  "service-call": getServiceCallActions,
  "sftp-download": getSftpDownloadActions,
  "sftp-trigger": getSftpTriggerActions,
  "sftp-trigger-2": getSftpTriggerActions,
  "sftp-upload": getSftpUploadActions,
  split: getSplitActions,
  "split-2": getSplitActions,
  "split-async": getSplitAsyncActions,
  "split-async-2": getSplitAsyncActions,
  "try-catch-finally": getTryCatchFinallyActions,
  "try-catch-finally-2": getTryCatchFinallyActions,
};

type ChainDependencies = {
  serviceMap: Map<string, IntegrationSystem>; // service ID -> service
  environmentMap: Map<string, Environment>; // environment ID -> environments
  chainMap: Map<string, Chain>; // element ID -> chain
};

type DiagramBuildContext = {
  mode: DiagramMode;
  chain: Chain;
  elementMap: Map<string, Element>;
  connections: Map<string, [string]>;
  dependencies: ChainDependencies;
};

type ParticipantsGetter = (
  element: Element,
  context: DiagramBuildContext,
) => Participant[];

type TriggerActionsGetter = (
  element: Element,
  context: DiagramBuildContext,
) => Action[];

export async function buildSequenceDiagram(
  chain: Chain,
  mode: DiagramMode,
): Promise<SequenceDiagram> {
  const context = createBuildContext(chain, mode);
  context.dependencies = await loadChainDependencies(context);

  const participants = [createChainParticipant(context)];
  const actions: Action[] = [];

  context.chain.elements
    .flatMap((element: Element) => {
      const participantsGetter = getParticipantsGetter(element.type);
      return participantsGetter(element, context);
    })
    .filter((participant) => {
      const participantAlreadyExists = participants.some(
        (p) => p.id === participant.id,
      );
      return !participantAlreadyExists;
    })
    .forEach((participant) => participants.push(participant));

  const triggers = getTriggers(context);

  triggers
    .flatMap((trigger) => {
      const actionsGetter = getElementActionsGetter(trigger);
      return actionsGetter(trigger, context);
    })
    .forEach((action) => actions.push(action));

  return {
    autonumber: true,
    participants,
    actions,
  };
}

function getAppName(): string {
  return "QIP"; // FIXME
}

function createBuildContext(
  chain: Chain,
  mode: DiagramMode,
): DiagramBuildContext {
  const connections = new Map<string, [string]>();
  chain.dependencies.forEach((d) => {
    if (connections.has(d.from)) {
      connections.get(d.from)?.push(d.to);
    } else {
      connections.set(d.from, [d.to]);
    }
  });
  return {
    mode,
    chain,
    elementMap: new Map<string, Element>(
      collectChainElements(chain.elements).map((e) => [e.id, e]),
    ),
    connections,
    dependencies: {
      serviceMap: new Map<string, IntegrationSystem>(),
      chainMap: new Map<string, Chain>(),
      environmentMap: new Map<string, Environment>(),
    },
  };
}

function collectChainElements(elements: Element[]): Element[] {
  if (elements.length === 0) {
    return [];
  }
  const result: Element[] = [...elements];
  result.push(
    ...collectChainElements(elements.flatMap((e) => e.children ?? [])),
  );
  return result;
}

async function loadChainDependencies(
  context: DiagramBuildContext,
): Promise<ChainDependencies> {
  const elementIds: string[] = Array.from(context.elementMap.values())
    .flatMap((element) => {
      return [
        element.properties["elementId"] as string,
        element.properties["idempotency"]?.["chainTriggerParameters"]?.[
          "triggerElementId"
        ],
        element.properties["chainFailureHandlerContainer"]?.["elementId"],
      ];
    })
    .filter((id) => !!id);

  const chainMap = new Map<string, Chain>(
    await Promise.all(
      elementIds.map<Promise<[string, Chain]>>(async (id) => [
        id,
        await api.findChainByElementId(id),
      ]),
    ),
  );

  const serviceIds = context.chain.elements
    .map((element) => element.properties["integrationSystemId"] as string)
    .filter((id) => !!id);
  const services = await Promise.all(
    serviceIds.map(async (id) => api.getService(id)),
  );

  const environments = (
    await Promise.all(serviceIds.map(async (id) => api.getEnvironments(id)))
  ).flatMap((e) => e);

  return {
    chainMap,
    serviceMap: new Map<string, IntegrationSystem>(
      services.map((s) => [s.id, s]),
    ),
    environmentMap: new Map<string, Environment>(
      environments.map((e) => [e.id, e]),
    ),
  };
}

function isTrigger(type: string): boolean {
  return TRIGGERS.includes(type);
}

function getTriggers(context: DiagramBuildContext): Element[] {
  return context.chain.elements.filter((element) => isTrigger(element.type));
}

function createChainParticipant(context: DiagramBuildContext): Participant {
  return {
    id: context.chain.id,
    name: `${getAppName()} chain: ${context.chain.name}`,
  };
}

function getElementActionsGetter(trigger: Element): TriggerActionsGetter {
  return ELEMENT_ACTIONS_GETTERS[trigger.type] ?? defaultElementActionsGetter;
}

function getParticipantsGetter(elementType: string): ParticipantsGetter {
  return PARTICIPANTS_GETTERS[elementType] ?? (() => []);
}

function getChainTriggerParticipants(
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _element: Element,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _context: DiagramBuildContext,
): Participant[] {
  return [createSimpleParticipant(`Unknown ${getAppName()} chain`)];
}

function getSftpParticipants(
  element: Element,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _context: DiagramBuildContext,
): Participant[] {
  const connectUrl =
    (element.properties["connectUrl"] as string) ?? EMPTY_PROPERTY_STUB;
  return [createSimpleParticipant(`SFTP server: ${connectUrl}`)];
}

function getContextStorageParticipants(
  element: Element,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _context: DiagramBuildContext,
): Participant[] {
  const contextServiceId =
    (element.properties["contextServiceId"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    createSimpleParticipant(`Context storage service: ${contextServiceId}`),
  ];
}

function getGraphQlSenderParticipants(
  element: Element,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _context: DiagramBuildContext,
): Participant[] {
  const uri = (element.properties["uri"] as string) ?? EMPTY_PROPERTY_STUB;
  return [createSimpleParticipant(`GraphQL server: ${uri}`)];
}

function getKafkaParticipants(
  element: Element,
  context: DiagramBuildContext,
): Participant[] {
  const connectionSourceType = element.properties[
    "connectionSourceType"
  ] as string;
  const name =
    connectionSourceType === "maas"
      ? "Kafka (MaaS)"
      : `Kafka ${(element.properties["brokers"] as string) ?? EMPTY_PROPERTY_STUB}`;
  return [
    createSimpleParticipant(name),
    ...getIdempotencyParticipants(element, context),
  ];
}

function getRabbitMqParticipants(
  element: Element,
  context: DiagramBuildContext,
): Participant[] {
  const connectionSourceType = element.properties[
    "connectionSourceType"
  ] as string;
  const name =
    connectionSourceType === "maas"
      ? "RabbitMQ (MaaS)"
      : `RabbitMQ ${(element.properties["addresses"] as string) ?? EMPTY_PROPERTY_STUB}`;
  return [
    createSimpleParticipant(name),
    ...getIdempotencyParticipants(element, context),
  ];
}

function getPubSubParticipants(
  element: Element,
  context: DiagramBuildContext,
): Participant[] {
  const projectId =
    (element.properties["projectId"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    createSimpleParticipant(`Google Cloud PubSub Project Id ${projectId}`),
    ...getIdempotencyParticipants(element, context),
  ];
}

function getChainCallParticipants(
  element: Element,
  context: DiagramBuildContext,
): Participant[] {
  const elementId =
    (element.properties["elementId"] as string) ?? EMPTY_PROPERTY_STUB;
  const chain = context.dependencies.chainMap.get(elementId);
  return [
    {
      id: elementId,
      name: `${getAppName()} chain: ${chain?.name ?? EMPTY_PROPERTY_STUB}`,
    },
  ];
}

function getMailParticipants(
  element: Element,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _context: DiagramBuildContext,
): Participant[] {
  const url = (element.properties["url"] as string) ?? EMPTY_PROPERTY_STUB;
  return [createSimpleParticipant(`Mail server: ${url}`)];
}

function getServiceCallParticipants(
  element: Element,
  context: DiagramBuildContext,
): Participant[] {
  const serviceId =
    (element.properties["integrationSystemId"] as string) ??
    EMPTY_PROPERTY_STUB;
  const service = context.dependencies.serviceMap.get(serviceId);
  return [
    { id: serviceId, name: `Service: ${service?.name ?? EMPTY_PROPERTY_STUB}` },
  ];
}

function getAsyncApiTriggerParticipants(
  element: Element,
  context: DiagramBuildContext,
): Participant[] {
  return [
    ...getServiceCallParticipants(element, context),
    ...getIdempotencyParticipants(element, context),
  ];
}

function getHttpSenderParticipants(
  element: Element,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _context: DiagramBuildContext,
): Participant[] {
  const uri = (element.properties["uri"] as string) ?? EMPTY_PROPERTY_STUB;
  const isExternalCall = element.properties["isExternalCall"];
  const host =
    /^https?:\/\/[^:/]+(:\\d{1,5})?/.exec(uri)?.[0] ?? EMPTY_PROPERTY_STUB;
  const name = `${isExternalCall === undefined || isExternalCall === null || Boolean(isExternalCall) ? "External" : "Internal"} service: ${host ?? EMPTY_PROPERTY_STUB}`;
  return [createSimpleParticipant(name)];
}

function getCheckpointParticipants(
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _element: Element,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _context: DiagramBuildContext,
): Participant[] {
  return [createSimpleParticipant("Unknown user")];
}

function getHttpTriggerParticipants(
  element: Element,
  context: DiagramBuildContext,
): Participant[] {
  const serviceId =
    (element.properties["integrationSystemId"] as string) ??
    EMPTY_PROPERTY_STUB;
  const serviceName =
    context.dependencies.serviceMap.get(serviceId)?.name ?? EMPTY_PROPERTY_STUB;
  const isManualSource = !element.properties["systemType"];
  const isExternal = Boolean(element.properties["externalRoute"] ?? true);
  const isPrivate = Boolean(element.properties["privateRoute"] ?? false);
  const route =
    isExternal && isPrivate
      ? "external or private"
      : isExternal
        ? "external"
        : "private";
  const name = isManualSource
    ? `Unknown ${isExternal || isPrivate ? `external (via ${route} route)` : "internal"} service`
    : serviceName
      ? `Service: ${serviceName}`
      : "Unknown service";
  const id = isManualSource ? name : serviceId;
  return [{ id, name }, ...getIdempotencyParticipants(element, context)];
}

function getIdempotencyParticipants(
  element: Element,
  context: DiagramBuildContext,
): Participant[] {
  const enabled = Boolean(
    element.properties["idempotency"]?.["enabled"] ?? false,
  );
  const isChainCall =
    element.properties["idempotency"]?.["actionOnDuplicate"] ===
    "execute-subchain";
  const elementId =
    element.properties["idempotency"]?.["chainTriggerParameters"]?.[
      "triggerElementId"
    ];
  return enabled && isChainCall
    ? [
        {
          id: elementId,
          name: `${getAppName()} chain: ${context.dependencies.chainMap.get(elementId)?.name ?? EMPTY_PROPERTY_STUB}`,
        },
      ]
    : [];
}

function createSimpleParticipant(name: string): Participant {
  return { id: name, name };
}

function defaultElementActionsGetter(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  return context.mode === DiagramMode.FULL
    ? [
        {
          type: "message",
          fromId: context.chain.id,
          toId: context.chain.id,
          arrowType: "arrow-solid",
          message: element.name,
        },
      ]
    : [];
}

function getSftpDownloadActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getSftpParticipants(element, context)[0];
  const fileName =
    (element.properties["fileName"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `Download file: ${fileName}`,
    },
  ];
}

function getSftpUploadActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getSftpParticipants(element, context)[0];
  const fileName =
    (element.properties["fileName"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `Upload file: ${fileName}`,
    },
  ];
}

function getFileReadActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const fileName =
    (element.properties["fileName"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: context.chain.id,
      arrowType: "arrow-solid",
      message: `Read local file: ${fileName}`,
    },
  ];
}

function getFileWriteActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const fileName =
    (element.properties["fileName"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: context.chain.id,
      arrowType: "arrow-solid",
      message: `Write local file: ${fileName}`,
    },
  ];
}

function getChainCallActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getChainCallParticipants(element, context)[0];
  const elementId =
    (element.properties["elementId"] as string) ?? EMPTY_PROPERTY_STUB;
  const chain = context.dependencies.chainMap.get(elementId);
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `${getAppName()} chain trigger call: ${chain?.name ?? elementId}`,
    },
    { type: "activate", participantId: participant.id },
    {
      type: "message",
      fromId: participant.id,
      toId: context.chain.id,
      arrowType: "arrow-dotted",
      message: DEFAULT_RESPONSE_TITLE,
    },
    { type: "deactivate", participantId: participant.id },
  ];
}

function getChainTriggerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getChainTriggerParticipants(element, context)[0];
  const actions: Action[] = [
    {
      type: "message",
      fromId: participant.id,
      toId: context.chain.id,
      arrowType: "arrow-solid",
      message: `${getAppName()} chain call`,
    },
    { type: "activate", participantId: context.chain.id },
  ];
  actions.push(
    ...(context.connections.get(element.id) ?? []).flatMap((id) =>
      getActionsForElementTree(id, context),
    ),
  );
  actions.push({
    type: "message",
    fromId: context.chain.id,
    toId: participant.id,
    arrowType: "arrow-dotted",
    message: DEFAULT_RESPONSE_TITLE,
  });
  actions.push({ type: "deactivate", participantId: context.chain.id });
  return [{ type: "group", label: element.name, actions }];
}

function getActionsForElementTree(
  id: string,
  context: DiagramBuildContext,
): Action[] {
  const element = context.elementMap.get(id);
  if (!element) {
    return [];
  }
  const actionsGetter = getElementActionsGetter(element);
  const actions = actionsGetter(element, context);
  actions.push(
    ...(context.connections.get(element.id) ?? []).flatMap((id) =>
      getActionsForElementTree(id, context),
    ),
  );
  return actions;
}

function getRabbitMqSenderActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getRabbitMqParticipants(element, context)[0];
  const exchange =
    (element.properties["exchange"] as string) ?? EMPTY_PROPERTY_STUB;
  const queues =
    (element.properties["queues"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `Put message to exchange: ${exchange}, queue: ${queues}`,
    },
  ];
}

function getKafkaSenderActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getKafkaParticipants(element, context)[0];
  const connectionSourceType = element.properties[
    "connectionSourceType"
  ] as string;
  const propertyName =
    connectionSourceType === "maas" ? "topicsClassifierName" : "topics";
  const topics =
    (element.properties[propertyName] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `Put message to topic(s): ${topics}`,
    },
  ];
}

function getGraphQlSenderActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getGraphQlSenderParticipants(element, context)[0];
  const operationName =
    (element.properties["operationName"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `GraphQL request (query/mutation), operation: ${operationName}`,
    },
    { type: "activate", participantId: participant.id },
    {
      type: "message",
      fromId: participant.id,
      toId: context.chain.id,
      arrowType: "arrow-dotted",
      message: DEFAULT_RESPONSE_TITLE,
    },
    { type: "deactivate", participantId: participant.id },
  ];
}

function getLoopActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const label =
    (element.properties["expression"] as string) ?? EMPTY_PROPERTY_STUB;
  const actions: Action[] = getActionsForChildren(element, context);
  return [{ type: "loop", label, actions }];
}

function getActionsForChildren(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  return (
    element.children
      ?.filter((e) => hasNoInputConnections(e, context))
      .flatMap((e) => getActionsForElementTree(e.id, context)) ?? []
  );
}

function hasNoInputConnections(
  element: Element,
  context: DiagramBuildContext,
): boolean {
  return !Array.from(context.connections.values())
    .flatMap((c) => c)
    .includes(element.id);
}

function getConditionActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const branches: Branch[] = [];
  element.children
    ?.map((e) => {
      const condition =
        (e.properties["condition"] as string) ?? EMPTY_PROPERTY_STUB;
      const label = e.type.startsWith("if")
        ? `${e.name}, on condition ${condition}`
        : e.name;
      const actions: Action[] = getActionsForChildren(e, context);
      return { type: "branch" as const, label, actions };
    })
    .forEach((b) => branches.push(b));
  return [{ type: "alternatives", branches }];
}

function getChoiceActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const branches: Branch[] = [];
  element.children
    ?.map((e) => {
      const condition =
        (e.properties["condition"] as string) ?? EMPTY_PROPERTY_STUB;
      const label = e.type.startsWith("when")
        ? `${e.name}, on condition ${condition}`
        : e.name;
      const actions: Action[] = getActionsForChildren(e, context);
      return { type: "branch" as const, label, actions };
    })
    .forEach((b) => branches.push(b));
  return [{ type: "alternatives", branches }];
}

function getMailSenderActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getMailParticipants(element, context)[0];
  const from = (element.properties["from"] as string) ?? EMPTY_PROPERTY_STUB;
  const to = (element.properties["to"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `Send mail from: ${from}, to: ${to}`,
    },
  ];
}

function getPubSubSenderActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getPubSubParticipants(element, context)[0];
  const destinationName =
    (element.properties["destinationName"] as string) ?? EMPTY_PROPERTY_STUB;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `Put message to ${destinationName}: ${destinationName}`,
    },
  ];
}

function getSplitAsyncActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const branches: Branch[] = [];
  element.children
    ?.map((e) => {
      const actions: Action[] = getActionsForChildren(e, context);
      return { type: "branch" as const, label: e.name, actions };
    })
    .forEach((b) => branches.push(b));
  return [{ type: "parallel", branches }];
}

function getSplitActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  return [
    ...getSplitAsyncActions(element, context),
    {
      type: "message",
      fromId: context.chain.id,
      toId: context.chain.id,
      arrowType: "arrow-solid",
      message: "Waiting for 'split elements' to complete",
    },
  ];
}

function getSchedulerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const expression =
    ((element.properties["scheduler.cron"] ??
      element.properties["cron"]) as string) ?? EMPTY_PROPERTY_STUB;
  const actions: Action[] = [
    { type: "activate", participantId: context.chain.id },
    {
      type: "message",
      fromId: context.chain.id,
      toId: context.chain.id,
      arrowType: "arrow-solid",
      message: `Invoke by cron expression: ${expression}`,
    },
  ];
  actions.push(
    ...(context.connections.get(element.id) ?? []).flatMap((id) =>
      getActionsForElementTree(id, context),
    ),
  );
  actions.push({ type: "deactivate", participantId: context.chain.id });
  return [{ type: "group", label: element.name, actions }];
}

function getTryCatchFinallyActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const branches: Branch[] = [];
  element.children
    ?.map((e) => {
      const exception =
        (e.properties["exception"] as string) ?? EMPTY_PROPERTY_STUB;
      const label = e.type.startsWith("catch")
        ? `${e.name}, on exception ${exception}`
        : e.name;
      const actions: Action[] = getActionsForChildren(e, context);
      return { type: "branch" as const, label, actions };
    })
    .forEach((b) => branches.push(b));
  return [{ type: "alternatives", branches }];
}

function getCircuitBreakerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const branches: Branch[] = [];
  element.children
    ?.map((e) => {
      const failureRateThreshold =
        (e.properties["failureRateThreshold"] as string) ?? EMPTY_PROPERTY_STUB;
      const label = e.type.startsWith("circuit-breaker-configuration")
        ? `Failure rate < ${failureRateThreshold}%`
        : e.name;
      const actions: Action[] = getActionsForChildren(e, context);
      return { type: "branch" as const, label, actions };
    })
    .forEach((b) => branches.push(b));
  return [{ type: "alternatives", branches }];
}

function getSftpTriggerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getSftpParticipants(element, context)[0];
  const antInclude =
    (element.properties["natInclude"] as string) ?? EMPTY_PROPERTY_STUB;
  const actions: Action[] = [
    { type: "activate", participantId: context.chain.id },
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message: `Download created/updated file: ${antInclude}`,
    },
  ];
  actions.push(
    ...(context.connections.get(element.id) ?? []).flatMap((id) =>
      getActionsForElementTree(id, context),
    ),
  );
  actions.push({ type: "deactivate", participantId: context.chain.id });
  return [{ type: "group", label: element.name, actions }];
}

function getHttpSenderActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getHttpSenderParticipants(element, context)[0];
  const methods =
    (element.properties["httpMethod"] as string) ?? EMPTY_PROPERTY_STUB;
  const uri = element.properties["uri"] as string;
  const host = uri
    ? /^https?:\/\/[^:/]+(:\\d{1,5})?/.exec(uri)?.[0]
    : undefined;
  const path = host ? uri.substring(host.length) : EMPTY_PROPERTY_STUB;
  const message = `${methods}, ${path}`;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message,
    },
    {
      type: "activate",
      participantId: participant.id,
    },
    {
      type: "message",
      fromId: participant.id,
      toId: context.chain.id,
      arrowType: "arrow-dotted",
      message: DEFAULT_RESPONSE_TITLE,
    },
    {
      type: "deactivate",
      participantId: participant.id,
    },
  ];
}

function getReuseReferenceActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const elementId = element.properties["reuseElementId"] as string;
  const reuse = context.elementMap.get(elementId);
  const label = `Reuse reference: ${reuse?.name ?? elementId ?? EMPTY_PROPERTY_STUB}`;
  const actions = reuse ? getActionsForChildren(reuse, context) : [];
  return [{ type: "group", label, actions }];
}

function getCheckpointActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getCheckpointParticipants(element, context)[0];
  const checkpointElementId =
    (element.properties["checkpointElementId"] as string) ??
    EMPTY_PROPERTY_STUB;
  const label = `${element.name} with id ${checkpointElementId}`;
  return [
    {
      type: "group",
      label,
      actions: [
        {
          type: "alternatives",
          branches: [
            {
              type: "branch",
              label: "Trigger",
              actions: [
                {
                  type: "message",
                  fromId: participant.id,
                  toId: context.chain.id,
                  arrowType: "arrow-solid",
                  message: "Request to retry session",
                },
                {
                  type: "message",
                  fromId: context.chain.id,
                  toId: context.chain.id,
                  arrowType: "arrow-solid",
                  message: "Load context",
                },
              ],
            },
            {
              type: "branch",
              label: "Checkpoint",
              actions: [
                {
                  type: "message",
                  fromId: context.chain.id,
                  toId: context.chain.id,
                  arrowType: "arrow-solid",
                  message: "Save context",
                },
              ],
            },
          ],
        },
      ],
    },
  ];
}

function getContextStorageActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getContextStorageParticipants(element, context)[0];
  const operation = (element.properties["operation"] as string) ?? "GET";
  const contextId = element.properties["contextId"] as string;
  const useCorrelationId = Boolean(
    (element.properties["useCorrelationId"] as string) ?? false,
  );
  const message = `${operation} context ${useCorrelationId ? "(use correlation ID)" : (contextId ?? EMPTY_PROPERTY_STUB)}`;
  return [
    {
      type: "message",
      fromId: context.chain.id,
      toId: participant.id,
      arrowType: "arrow-solid",
      message,
    },
    {
      type: "activate",
      participantId: participant.id,
    },
    {
      type: "message",
      fromId: participant.id,
      toId: context.chain.id,
      arrowType: "arrow-dotted",
      message: DEFAULT_RESPONSE_TITLE,
    },
    {
      type: "deactivate",
      participantId: participant.id,
    },
  ];
}

function getPubSubTriggerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const [participant, idempotencyParticipant] = getContextStorageParticipants(
    element,
    context,
  );
  const destinationName =
    (element.properties["destinationName"] as string) ?? EMPTY_PROPERTY_STUB;
  const actions = (context.connections.get(element.id) ?? []).flatMap((id) =>
    getActionsForElementTree(id, context),
  );
  const request: Message = {
    type: "message",
    fromId: participant.id,
    toId: context.chain.id,
    arrowType: "arrow-solid",
    message: `Read message from ${destinationName}: ${destinationName}`,
  };
  return getActionsForIdempotentTrigger(
    element,
    context,
    request,
    undefined,
    actions,
    idempotencyParticipant,
  );
}

function getAsyncApiTriggerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const [participant, idempotencyParticipant] = getAsyncApiTriggerParticipants(
    element,
    context,
  );
  const actions = [
    ...getValidateRequestActions(element, context),
    ...(context.connections.get(element.id) ?? []).flatMap((id) =>
      getActionsForElementTree(id, context),
    ),
  ];
  const request: Message = {
    type: "message",
    fromId: participant.id,
    toId: context.chain.id,
    arrowType: "arrow-solid",
    message: `Pull message from ${getAsyncApiSource(element, context)}`,
  };
  return getActionsForIdempotentTrigger(
    element,
    context,
    request,
    undefined,
    actions,
    idempotencyParticipant,
  );
}

function getAsyncApiSource(
  element: Element,
  context: DiagramBuildContext,
): string {
  const serviceId = element.properties["integrationSystemId"] as string;
  if (!serviceId) {
    return EMPTY_PROPERTY_STUB;
  }
  const service = context.dependencies.serviceMap.get(serviceId);
  if (!service) {
    return EMPTY_PROPERTY_STUB;
  }
  const environment = context.dependencies.environmentMap.get(
    service.activeEnvironmentId,
  );
  if (!environment) {
    return (
      element.properties["integrationOperationPath"] ?? EMPTY_PROPERTY_STUB
    );
  }
  const sourceType = environment.sourceType;

  const protocol = element.properties[
    "integrationOperationProtocolType"
  ] as string;

  const asyncProperties = element.properties[
    "integrationOperationAsyncProperties"
  ] as Record<string, unknown>;
  switch (protocol) {
    case "amqp": {
      const source =
        sourceType === EnvironmentSourceType.MANUAL
          ? (((environment.properties?.["queues"] ??
              asyncProperties["queues"]) as string) ?? EMPTY_PROPERTY_STUB)
          : `by classifier ${((environment.properties?.["maas.classifier.name"] ?? asyncProperties["maas.classifier.name"]) as string) ?? EMPTY_PROPERTY_STUB}`;
      return `queue ${source}`;
    }
    case "kafka": {
      const source =
        sourceType === EnvironmentSourceType.MANUAL
          ? (((environment.properties?.["topic"] ??
              asyncProperties["integrationOperationPath"]) as string) ??
            EMPTY_PROPERTY_STUB)
          : `by classifier ${((environment.properties?.["maas.classifier.name"] ?? asyncProperties["maas.classifier.name"]) as string) ?? EMPTY_PROPERTY_STUB}`;
      return `topic ${source}`;
    }
    default: {
      return EMPTY_PROPERTY_STUB;
    }
  }
}

function getKafkaTriggerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const [participant, idempotencyParticipant] = getKafkaParticipants(
    element,
    context,
  );
  const actions = (context.connections.get(element.id) ?? []).flatMap((id) =>
    getActionsForElementTree(id, context),
  );
  const connectionSourceType = element.properties[
    "connectionSourceType"
  ] as string;
  const source =
    connectionSourceType === "manual"
      ? ((element.properties["topics"] as string) ?? EMPTY_PROPERTY_STUB)
      : `by classifier ${(element.properties["topicsClassifierName"] as string) ?? EMPTY_PROPERTY_STUB}`;
  const request: Message = {
    type: "message",
    fromId: participant.id,
    toId: context.chain.id,
    arrowType: "arrow-solid",
    message: `Pull message from topic(s): ${source}`,
  };
  return getActionsForIdempotentTrigger(
    element,
    context,
    request,
    undefined,
    actions,
    idempotencyParticipant,
  );
}

function getRabbitMqTriggerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const [participant, idempotencyParticipant] = getRabbitMqParticipants(
    element,
    context,
  );
  const actions = (context.connections.get(element.id) ?? []).flatMap((id) =>
    getActionsForElementTree(id, context),
  );
  const connectionSourceType = element.properties[
    "connectionSourceType"
  ] as string;
  const queues =
    (element.properties["queues"] as string) ?? EMPTY_PROPERTY_STUB;
  const exchange =
    (element.properties["exchange"] as string) ?? EMPTY_PROPERTY_STUB;
  const classifier =
    (element.properties["vhostClassifierName"] as string) ??
    EMPTY_PROPERTY_STUB;
  const message =
    connectionSourceType === "manual"
      ? `Pull message from exchange: ${exchange}, queues: ${queues}`
      : `Pull message by classifier ${classifier}`;
  const request: Message = {
    type: "message",
    fromId: participant.id,
    toId: context.chain.id,
    arrowType: "arrow-solid",
    message,
  };
  return getActionsForIdempotentTrigger(
    element,
    context,
    request,
    undefined,
    actions,
    idempotencyParticipant,
  );
}

function getHttpTriggerActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const [participant, idempotencyParticipant] = getHttpTriggerParticipants(
    element,
    context,
  );
  const isManualSource = !element.properties["systemType"];
  const methods = (element.properties["httpMethodRestrict"] as string) ?? "ALL";
  const uri = isManualSource
    ? (element.properties["contextPath"] as string)
    : (element.properties["integrationOperationPath"] as string);
  const message = `HTTP request to ${uri ?? EMPTY_PROPERTY_STUB}, allowed methods=[${methods}]`;
  const request: Message = {
    type: "message",
    fromId: participant.id,
    toId: context.chain.id,
    arrowType: "arrow-solid",
    message,
  };
  const response: Message = {
    type: "message",
    fromId: context.chain.id,
    toId: participant.id,
    arrowType: "arrow-dotted",
    message: DEFAULT_RESPONSE_TITLE,
  };
  const actions = [
    ...(isManualSource ? [] : getValidateRequestActions(element, context)),
    ...(context.connections.get(element.id) ?? []).flatMap((id) =>
      getActionsForElementTree(id, context),
    ),
  ];
  return getActionsForIdempotentTrigger(
    element,
    context,
    request,
    response,
    actions,
    idempotencyParticipant,
  );
}

function getActionsForIdempotentTrigger(
  element: Element,
  context: DiagramBuildContext,
  request: Message,
  response: Message | undefined,
  actions: Action[],
  idempotencyParticipant: Participant,
): Action[] {
  const elementId =
    element.properties["idempotency"]?.["chainTriggerParameters"]?.[
      "triggerElementId"
    ];
  const chain = context.dependencies.chainMap.get(elementId);
  const enabled = Boolean(
    element.properties["idempotency"]?.["enabled"] ?? false,
  );
  const actionOnDuplicate = element.properties["idempotency"]?.[
    "actionOnDuplicate"
  ] as string;
  return [
    {
      type: "group",
      label: element.name,
      actions: [
        request,
        { type: "activate", participantId: context.chain.id },
        ...(enabled
          ? ([
              {
                type: "alternatives",
                branches:
                  actionOnDuplicate === "execute-subchain"
                    ? [
                        { type: "branch", label: "on message", actions },
                        {
                          type: "branch",
                          label: "on duplicate message",
                          actions: [
                            {
                              type: "message",
                              fromId: context.chain.id,
                              toId: idempotencyParticipant.id,
                              arrowType: "arrow-solid",
                              message: `${getAppName()} chain trigger call: ${chain?.name ?? elementId}`,
                            },
                            {
                              type: "activate",
                              participantId: idempotencyParticipant.id,
                            },
                            {
                              type: "message",
                              fromId: idempotencyParticipant.id,
                              toId: context.chain.id,
                              arrowType: "arrow-dotted",
                              message: DEFAULT_RESPONSE_TITLE,
                            },
                            {
                              type: "deactivate",
                              participantId: idempotencyParticipant.id,
                            },
                          ],
                        },
                      ]
                    : actionOnDuplicate === "throw-exception"
                      ? [
                          {
                            type: "message",
                            fromId: context.chain.id,
                            toId: context.chain.id,
                            arrowType: "arrow-solid",
                            message: "Throw exception",
                          },
                        ]
                      : actionOnDuplicate === "ignore"
                        ? [
                            {
                              type: "message",
                              fromId: context.chain.id,
                              toId: context.chain.id,
                              arrowType: "arrow-solid",
                              message: "Ignore",
                            },
                          ]
                        : [],
              },
            ] as Action[])
          : actions),
        ...(response ? [response] : []),
        { type: "deactivate", participantId: context.chain.id },
      ],
    },
  ];
}

function getValidateRequestActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const after = element.properties["after"] as Record<string, string>[];
  return (after?.length ?? 0) > 0
    ? [
        {
          type: "alternatives",
          branches: after.map((a) => ({
            type: "branch",
            label: `Schema: ${a["label"] ?? EMPTY_PROPERTY_STUB}`,
            actions: [
              {
                type: "message",
                fromId: context.chain.id,
                toId: context.chain.id,
                arrowType: "arrow-solid",
                message: "Validate request",
              },
            ],
          })),
        },
      ]
    : [];
}

function getServiceCallActions(
  element: Element,
  context: DiagramBuildContext,
): Action[] {
  const participant = getServiceCallParticipants(element, context)[0];
  const before = element.properties["before"] as Record<string, unknown>;
  const after = element.properties["after"] as Record<string, unknown>[];
  const protocol = element.properties["integrationOperationProtocolType"];
  const actions: Action[] = [];
  const beforeType = before?.["type"];

  if (beforeType) {
    actions.push({
      type: "message",
      fromId: context.chain.id,
      toId: context.chain.id,
      arrowType: "arrow-solid",
      message: `Prepare request (${beforeType as string})`,
    });
  }

  const message = getServiceCallRequestMessage(element, context);

  actions.push({
    type: "message" as const,
    fromId: context.chain.id,
    toId: participant.id,
    arrowType: "arrow-solid" as const,
    message,
  });

  if (protocol === "http") {
    actions.push(
      ...[
        { type: "activate" as const, participantId: participant.id },
        {
          type: "message" as const,
          fromId: participant.id,
          toId: context.chain.id,
          arrowType: "arrow-dotted" as const,
          message: DEFAULT_RESPONSE_TITLE,
        },
        { type: "deactivate" as const, participantId: participant.id },
      ],
    );
  }

  if ((after?.length ?? 0) > 0) {
    actions.push({
      type: "alternatives",
      branches: after.map((a) => ({
        type: "branch",
        label: `Code ${(a["label"] as string) ?? EMPTY_PROPERTY_STUB}`,
        actions: [
          {
            type: "message",
            fromId: context.chain.id,
            toId: context.chain.id,
            arrowType: "arrow-solid",
            message: `Handle response (${(a["type"] as string) ?? EMPTY_PROPERTY_STUB})`,
          },
        ],
      })),
    });
  }
  return [{ type: "group", label: element.name, actions }];
}

function getServiceCallRequestMessage(
  element: Element,
  context: DiagramBuildContext,
): string {
  const serviceId = element.properties["integrationSystemId"] as string;
  if (!serviceId) {
    return EMPTY_PROPERTY_STUB;
  }
  const service = context.dependencies.serviceMap.get(serviceId);
  if (!service) {
    return EMPTY_PROPERTY_STUB;
  }
  const environment = context.dependencies.environmentMap.get(
    service.activeEnvironmentId,
  );
  if (!environment) {
    return (
      element.properties["integrationOperationPath"] ?? EMPTY_PROPERTY_STUB
    );
  }
  const sourceType = environment.sourceType;
  const protocol = element.properties["integrationOperationProtocolType"];
  const asyncProperties = element.properties[
    "integrationOperationAsyncProperties"
  ] as Record<string, unknown>;
  switch (protocol) {
    case "amqp": {
      const source =
        sourceType === EnvironmentSourceType.MANUAL
          ? (((environment.properties?.["exchange"] ??
              asyncProperties["integrationOperationPath"]) as string) ??
            EMPTY_PROPERTY_STUB)
          : `by classifier ${((environment.properties?.["maas.classifier.name"] ?? asyncProperties["maas.classifier.name"]) as string) ?? EMPTY_PROPERTY_STUB}`;
      return `Put message to exchange ${source}`;
    }
    case "kafka": {
      const source =
        sourceType === EnvironmentSourceType.MANUAL
          ? (((environment.properties?.["topic"] ??
              asyncProperties["integrationOperationPath"]) as string) ??
            EMPTY_PROPERTY_STUB)
          : `by classifier ${((environment.properties?.["maas.classifier.name"] ?? asyncProperties["maas.classifier.name"]) as string) ?? EMPTY_PROPERTY_STUB}`;
      return `Put message to topic ${source}`;
    }
    case "graphql": {
      const operationName = element.properties[
        "integrationGqlOperationName"
      ] as string;
      return `GraphQL request (query/mutation) ${operationName ? `, operation: ${operationName}` : ""}`;
    }
    default: {
      const method =
        (element.properties["integrationOperationMethod"] as string) ??
        EMPTY_PROPERTY_STUB;
      const path =
        (element.properties["integrationOperationPath"] as string) ??
        EMPTY_PROPERTY_STUB;
      return `${method} ${path}`;
    }
  }
}
