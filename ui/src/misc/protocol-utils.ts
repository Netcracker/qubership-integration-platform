const normalizeProtocol = (value: string): string => {
  return value?.trim().toLowerCase();
};

const matchesProtocol = (value: string, candidates: string[]) => {
  const normalized = normalizeProtocol(value);
  return normalized ? candidates.includes(normalized) : false;
};

const isKafkaProtocol = (value: string) => matchesProtocol(value, ["kafka"]);

const isAmqpProtocol = (value: string) =>
  matchesProtocol(value, ["amqp", "rabbit"]);

const isHttpProtocol = (value: string) =>
  matchesProtocol(value, ["http", "soap"]);

const isAsyncProtocol = (value: string) =>
  isKafkaProtocol(value) || isAmqpProtocol(value);

const isGrpcProtocol = (value: string) => matchesProtocol(value, ["grpc"]);

/**
 * The protocol to publish into a chain element's form context. Readers match it against a transport
 * name — `isKafkaProtocol`, the oneOf branches, the Validations tab — so a service that carries no
 * protocol has to land on a usable default instead of the empty string.
 */
const protocolForContext = (value?: string): string =>
  normalizeProtocol(value ?? "") || "http";

export {
  normalizeProtocol,
  protocolForContext,
  isKafkaProtocol,
  isAmqpProtocol,
  isHttpProtocol,
  isAsyncProtocol,
  isGrpcProtocol,
};
