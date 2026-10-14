/**
 * What the service-call specs share: the body a mock answers, the defect that keeps the micro engine
 * from reaching the mock, and the one-operation OpenAPI document a spec imports into a service of its
 * own.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

export const HTTP_OPERATION = "e2eServiceCall";
export const ORDER_PATH = "/orders/{orderId}";
/** What an http mock answers. */
export const MOCKED_BODY = '{"mocked":"http"}';

/** What the egress gateway answers for a route whose upstream it cannot reach. */
const EGRESS_NO_ROUTE = /no healthy upstream/;

/**
 * The micro engine sends a test case run's service call to the service's own address rather than to
 * the endpoint mock. Measured on a micro domain: the pod logs `Endpoint mocking is enabled`, and the
 * call it then sends fails with `UnknownHostException: e2e-service-call.invalid`. No issue is filed
 * yet. The engine answers that exception with QIP-0101; an EXTERNAL call reaches the egress gateway
 * instead, which cannot reach the address either.
 */
export const MOCK_BYPASSED: ConditionalDefect = {
  title: "the micro engine sends a mocked service call to the real address",
  matches: (message) => message.includes("QIP-0101") || EGRESS_NO_ROUTE.test(message),
};

/** A one-operation OpenAPI document: `path` under `method`, with `orderId` declared when the path has it. */
export function openApi(method = "post", path = ORDER_PATH): { name: string; mimeType: string; buffer: Buffer } {
  const parameters = path.includes("{orderId}")
    ? ["      parameters:", "        - name: orderId", "          in: path", "          required: true", "          schema:", "            type: string"]
    : [];
  const buffer = Buffer.from(
    [
      "openapi: 3.0.3",
      "info:",
      "  title: E2E Service Call",
      "  version: 1.0.0",
      "paths:",
      `  ${path}:`,
      `    ${method}:`,
      `      operationId: ${HTTP_OPERATION}`,
      ...parameters,
      "      responses:",
      '        "201":',
      "          description: created",
    ].join("\n"),
  );
  return { name: "service-call.openapi.yaml", mimeType: "application/yaml", buffer };
}
