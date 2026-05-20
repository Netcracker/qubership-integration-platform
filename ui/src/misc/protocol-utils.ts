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

export {
  normalizeProtocol,
  isKafkaProtocol,
  isAmqpProtocol,
  isHttpProtocol,
  isAsyncProtocol,
  isGrpcProtocol,
};
