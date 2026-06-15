export function endpointColumnTitleForProtocol(
  protocol: string | undefined,
): string {
  switch (protocol?.toLowerCase()) {
    case "amqp":
      return "Channel";
    case "kafka":
      return "Topic";
    case "graphql":
      return "Operation";
    default: // grpc, http, soap
      return "URL";
  }
}
