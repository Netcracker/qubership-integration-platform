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
