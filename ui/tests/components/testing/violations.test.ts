import {
  EndpointMock,
  MatcherEntityType,
  MatcherType,
} from "../../../src/api/apiTypes.ts";
import {
  endpointMockViolations,
  introducesViolation,
} from "../../../src/components/testing/violations.ts";

function endpointMock(overrides: Partial<EndpointMock> = {}): EndpointMock {
  return {
    id: "mock-1",
    name: "First mock",
    description: "",
    enabled: true,
    endpointReference: { chainId: "chain-1", elementId: "element-1" },
    responseSettings: {
      message: { body: "{}", headers: [{ name: "Accept", value: "text/csv" }] },
      status: 200,
      delay: 0,
    },
    requestMatchers: [
      {
        id: "rule-1",
        name: "body exists",
        description: "",
        enabled: true,
        type: MatcherType.EXIST,
        entityType: MatcherEntityType.BODY,
        entityName: null,
        parameters: [],
      },
    ],
    createdBy: "author",
    createdAt: "2026-08-13T10:00:00.000Z",
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  };
}

function withStatus(status: number): EndpointMock {
  return endpointMock({
    responseSettings: { message: null, status, delay: 0 },
  });
}

describe("endpoint mock violations", () => {
  test("should report nothing for a mock the service accepts", () => {
    expect(endpointMockViolations(endpointMock())).toEqual([]);
    expect(endpointMockViolations(null)).toEqual([]);
  });

  test.each([42, 99, 600, 1000, -1, 200.5])(
    "should report the response status %s, which the service cannot answer with",
    (status) => {
      expect(endpointMockViolations(withStatus(status))).toEqual([
        `response status ${status}`,
      ]);
    },
  );

  // Zero is a mock that never named a status, and the service stores it as it is.
  test("should accept a status of zero and one inside the range", () => {
    expect(endpointMockViolations(withStatus(0))).toEqual([]);
    expect(endpointMockViolations(withStatus(100))).toEqual([]);
    expect(endpointMockViolations(withStatus(599))).toEqual([]);
  });

  test("should report a response header the service cannot write out", () => {
    expect(
      endpointMockViolations(
        endpointMock({
          responseSettings: {
            message: {
              body: null,
              headers: [
                { name: "Content Type", value: "text/csv" },
                { name: "X-Trace", value: "a\nb" },
              ],
            },
            status: 200,
            delay: 0,
          },
        }),
      ),
    ).toEqual([
      'response header name "Content Type"',
      'response header "X-Trace" value "a\\nb"',
    ]);
  });

  // The key names the offending status, so the mock stays editable while it keeps
  // the status it was stored with and shuts the save over any other bad one.
  test("should key the status violation by the value the service refuses", () => {
    const stored = endpointMockViolations(withStatus(42));

    expect(
      introducesViolation(endpointMockViolations(withStatus(42)), stored),
    ).toBe(false);
    expect(
      introducesViolation(endpointMockViolations(withStatus(99)), stored),
    ).toBe(true);
    expect(
      introducesViolation(endpointMockViolations(withStatus(404)), stored),
    ).toBe(false);
  });
});
