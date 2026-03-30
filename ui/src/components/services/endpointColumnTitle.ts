export function endpointColumnTitleForProtocol(
  protocol: string | undefined,
): string {
  if (!protocol) return "URL";
  const p = protocol.toUpperCase();
  if (
    p.includes("HTTP") ||
    p.includes("REST") ||
    p.includes("HTTPS") ||
    p.includes("SOAP")
  ) {
    return "URL";
  }
  if (p.includes("KAFKA") || p.includes("PULSAR") || p.includes("TOPIC")) {
    return "Topic";
  }
  return "Channel";
}
