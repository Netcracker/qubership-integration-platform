import { DomainType, ExecutionStatus } from "../../src/api/apiTypes.ts";
import type { Session, SessionElement } from "../../src/api/apiTypes.ts";
import {
  buildGraphUrl,
  getGraphChainId,
} from "../../src/misc/session-graph-utils.ts";

function baseSession(chainId: string): Session {
  return {
    id: "session-1",
    chainId,
    chainName: "Chain",
    started: "",
    finished: "",
    duration: 100,
    syncDuration: 0,
    executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
    importedSession: false,
    externalSessionCipId: "",
    domain: "d",
    domainType: DomainType.CLASSIC,
    engineAddress: "",
    loggingLevel: "INFO",
    snapshotName: "snap",
    correlationId: "",
    parentSessionId: "",
    sessionElements: [],
  };
}

function baseElement(overrides: Partial<SessionElement> & { elementId: string }): SessionElement {
  const { elementId, ...rest } = overrides;
  return {
    elementId,
    sessionId: "session-1",
    chainElementId: `ce-${elementId}`,
    actualElementChainId: "chain-1",
    parentElement: "",
    previousElement: "",
    elementName: "El",
    camelName: "script",
    bodyBefore: "",
    bodyAfter: "",
    headersBefore: {},
    headersAfter: {},
    propertiesBefore: {},
    propertiesAfter: {},
    contextBefore: {},
    contextAfter: {},
    started: "",
    finished: "",
    duration: 42,
    syncDuration: 0,
    executionStatus: ExecutionStatus.COMPLETED_NORMALLY,
    exceptionInfo: null,
    children: undefined,
    ...rest,
  } as SessionElement;
}

describe("getGraphChainId", () => {
  test("returns actualElementChainId for non-chain-call element", () => {
    const el = baseElement({ elementId: "e1", actualElementChainId: "sub-chain", camelName: "log-record" });
    expect(getGraphChainId(el, baseSession("session-chain"))).toBe("sub-chain");
  });

  test("returns session chainId for chain-call-2 caller even with actualElementChainId", () => {
    const el = baseElement({
      elementId: "e1",
      camelName: "chain-call-2",
      chainElementId: "cba96d04-4bf2-4042-955a-f0cbb174d507",
      actualElementChainId: "e30bc212-5952-4262-b610-8ee5fd77e0a7",
    });
    expect(getGraphChainId(el, baseSession("0b052b4d-60c1-4132-a921-71b6f771ef94"))).toBe(
      "0b052b4d-60c1-4132-a921-71b6f771ef94",
    );
  });

  test("returns session chainId for legacy chain-call caller", () => {
    const el = baseElement({ elementId: "e1", camelName: "chain-call", actualElementChainId: "sub" });
    expect(getGraphChainId(el, baseSession("sess"))).toBe("sess");
  });

  test("falls back to session chainId when actualElementChainId is null", () => {
    const el = { ...baseElement({ elementId: "e1" }), actualElementChainId: null as unknown as string };
    expect(getGraphChainId(el, baseSession("fallback"))).toBe("fallback");
  });

  test("falls back to session chainId when actualElementChainId is undefined", () => {
    const el = { ...baseElement({ elementId: "e1" }), actualElementChainId: undefined as unknown as string };
    expect(getGraphChainId(el, baseSession("fallback2"))).toBe("fallback2");
  });

  test("returns session chainId when element is undefined", () => {
    expect(getGraphChainId(undefined, baseSession("sess2"))).toBe("sess2");
  });

  test("uses actualElementChainId for child inside subchain", () => {
    const el = baseElement({
      elementId: "e-child",
      camelName: "log-record",
      chainElementId: "6b893a78-ab47-4678-966b-3819d662f499",
      actualElementChainId: "e30bc212-5952-4262-b610-8ee5fd77e0a7",
      parentElement: "5bc7b8ea-6a24-4ad1-a434-3ec3bf33e8c4",
    });
    expect(getGraphChainId(el, baseSession("0b052b4d-60c1-4132-a921-71b6f771ef94"))).toBe(
      "e30bc212-5952-4262-b610-8ee5fd77e0a7",
    );
  });
});

describe("buildGraphUrl", () => {
  test("builds URL for subchain bug report case", () => {
    const el = baseElement({
      elementId: "5bc7b8ea-6a24-4ad1-a434-3ec3bf33e8c4",
      camelName: "chain-call-2",
      chainElementId: "cba96d04-4bf2-4042-955a-f0cbb174d507",
      actualElementChainId: "e30bc212-5952-4262-b610-8ee5fd77e0a7",
    });
    const session = baseSession("0b052b4d-60c1-4132-a921-71b6f771ef94");
    expect(buildGraphUrl(el, session)).toBe(
      "/chains/0b052b4d-60c1-4132-a921-71b6f771ef94/graph/cba96d04-4bf2-4042-955a-f0cbb174d507",
    );
  });

  test("builds URL for inner element", () => {
    const el = baseElement({
      elementId: "inner",
      camelName: "log-record",
      chainElementId: "6b893a78-ab47-4678-966b-3819d662f499",
      actualElementChainId: "e30bc212-5952-4262-b610-8ee5fd77e0a7",
    });
    const session = baseSession("0b052b4d-60c1-4132-a921-71b6f771ef94");
    expect(buildGraphUrl(el, session)).toBe(
      "/chains/e30bc212-5952-4262-b610-8ee5fd77e0a7/graph/6b893a78-ab47-4678-966b-3819d662f499",
    );
  });
});
