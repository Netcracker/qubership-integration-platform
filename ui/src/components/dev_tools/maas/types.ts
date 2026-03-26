/**
 * Returns namespace from globalThis.window.routes or empty string when not set.
 */
export function getMaasDefaultNamespace(): string {
  if (typeof globalThis.window !== "undefined") {
    return (
      (globalThis.window as { routes?: { namespace?: string } }).routes
        ?.namespace ?? ""
    );
  }
  return "";
}

/**
 * Non-whitespace pattern validator for MaaS form fields.
 * Ensures the field contains at least one non-whitespace character.
 */
export const NON_WHITESPACE_PATTERN = /^\S+$/;

/**
 * Default vhost value for RabbitMQ MaaS forms.
 */
export const DEFAULT_VHOST = "public";

/**
 * Form field names for RabbitMQ MaaS.
 */
export const RABBITMQ_FIELD_NAMES = {
  NAMESPACE: "namespace",
  VHOST: "vhost",
  EXCHANGE: "exchange",
  QUEUE: "queue",
  ROUTING_KEY: "routingKey",
} as const;

/**
 * Form field names for Kafka MaaS.
 */
export const KAFKA_FIELD_NAMES = {
  NAMESPACE: "namespace",
  TOPIC_CLASSIFIER_NAME: "topicClassifierName",
} as const;

export interface KafkaMaasFormData {
  namespace: string;
  topicClassifierName: string;
}

export interface RabbitMQMaasFormData {
  namespace: string;
  vhost: string;
  exchange: string;
  queue: string;
  routingKey: string;
}

export interface MaasInstance {
  id: string;
  address: string;
  defaultInstance: boolean;
  protocol: string;
  properties: { [key: string]: string };
}

export interface KafkaTopic {
  topic: string;
  classifierName: string;
}

export interface MaasFormActionsProps {
  createInProgress: boolean;
  isFormValid: boolean;
  onCreate: () => void;
  onReset: () => void;
}

export interface MaasPageHeaderProps {
  title: string;
  exportInProgress: boolean;
  isFormValid: boolean;
  onExport: () => void;
}

/**
 * Pure validation function for RabbitMQ MaaS form.
 * Returns true when all required fields are filled and cross-field constraints are met.
 */
export function isRabbitMQFormValid(
  formValues: Partial<RabbitMQMaasFormData> | undefined,
): boolean {
  const namespace = formValues?.namespace;
  const vhost = formValues?.vhost;
  const exchange = formValues?.exchange?.trim() ?? "";
  const queue = formValues?.queue?.trim() ?? "";
  const routingKey = formValues?.routingKey?.trim() ?? "";
  if (
    !namespace ||
    !vhost ||
    !NON_WHITESPACE_PATTERN.test(namespace) ||
    !NON_WHITESPACE_PATTERN.test(vhost)
  ) {
    return false;
  }
  if (!exchange && !queue) return false;
  return !(routingKey && (!exchange || !queue));
}

/**
 * Pure validation function for Kafka MaaS form.
 * Returns true when namespace and topic classifier name are non-empty.
 */
export function isKafkaFormValid(
  formValues: Partial<KafkaMaasFormData> | undefined,
): boolean {
  const namespace = formValues?.namespace;
  const topicClassifierName = formValues?.topicClassifierName;
  return (
    !!namespace &&
    !!topicClassifierName &&
    NON_WHITESPACE_PATTERN.test(namespace) &&
    NON_WHITESPACE_PATTERN.test(topicClassifierName)
  );
}
