import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from "@jest/globals";
import { setAiServiceUrl } from "../../../src/ai/appConfig.ts";
import {
  approveChainPlanForBuild,
  dismissChainPlanOpenItems,
  fetchChainPlanDetail,
  fetchChainPlanStatus,
} from "../../../src/api/ai/chainPlanClient.ts";

const fetchMock = jest.fn<typeof fetch>();
const originalFetch = global.fetch;
const calls = [
  { call: fetchChainPlanStatus, path: "/status", init: undefined },
  { call: fetchChainPlanDetail, path: "", init: undefined },
  {
    call: dismissChainPlanOpenItems,
    path: "/dismiss-open-items",
    init: { method: "POST" },
  },
  {
    call: approveChainPlanForBuild,
    path: "/approve",
    init: { method: "POST" },
  },
];

beforeEach(() => {
  global.fetch = fetchMock;
  setAiServiceUrl("https://ai.example.test/");
});
afterEach(() => {
  global.fetch = originalFetch;
  setAiServiceUrl(undefined);
});

describe.each(calls)("chain plan $path", ({ call, path, init }) => {
  it("sends the request and returns the server response", async () => {
    const body = { planId: "plan-1", approved: true, openItemCount: 0 };
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify(body), { status: 200 }),
    );
    expect(await call("conv-1")).toEqual(body);
    expect(fetchMock.mock.calls[0]).toEqual(
      init
        ? [`https://ai.example.test/api/v1/chat/conv-1/chain-plan${path}`, init]
        : [`https://ai.example.test/api/v1/chat/conv-1/chain-plan${path}`],
    );
  });

  it("returns no plan after an unsuccessful HTTP response", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 404 }));
    expect(await call("conv-1")).toBeNull();
  });

  it("avoids a request when the service is not configured", async () => {
    setAiServiceUrl(undefined);
    expect(await call("conv-1")).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("propagates a network failure", async () => {
    fetchMock.mockRejectedValue(new Error("Network unavailable"));
    await expect(call("conv-1")).rejects.toThrow("Network unavailable");
  });
});
