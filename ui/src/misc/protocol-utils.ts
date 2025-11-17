type AnyProtocol = unknown;

const normalizeProtocol = (value: AnyProtocol): string | undefined => {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  return trimmed ? trimmed.toLowerCase() : undefined;
};

const matchesProtocol = (value: AnyProtocol, candidates: string[]) => {
  const normalized = normalizeProtocol(value);
  return normalized ? candidates.includes(normalized) : false;
};

const isKafkaProtocol = (value: AnyProtocol) => matchesProtocol(value, ["kafka"]);

const isAmqpProtocol = (value: AnyProtocol) =>
  matchesProtocol(value, ["amqp", "rabbit"]);

const isHttpProtocol = (value: AnyProtocol) =>
  matchesProtocol(value, ["http", "soap"]);

const isAsyncProtocol = (value: AnyProtocol) =>
  isKafkaProtocol(value) || isAmqpProtocol(value);

const isGrpcProtocol = (value: AnyProtocol) => matchesProtocol(value, ["grpc"]);

export {
  normalizeProtocol,
  isKafkaProtocol,
  isAmqpProtocol,
  isHttpProtocol,
  isAsyncProtocol,
  isGrpcProtocol,
};

