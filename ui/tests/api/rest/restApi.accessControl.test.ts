/**
 * @jest-environment jsdom
 */
import type { AxiosRequestConfig, AxiosAdapter } from "axios";

jest.mock("axios-rate-limit", () => ({
  __esModule: true,
  default: (instance: unknown) => instance,
}));

jest.mock("../../../src/appConfig", () => ({
  getConfig: () => ({ apiGateway: "https://api.example.com" }),
}));

jest.mock("../../../src/api/rest/requestHeadersInterceptor", () => ({
  registerRestAxiosInstance: jest.fn(),
}));

const recordingApi = async () => {
  const { RestApi } = await import("../../../src/api/rest/restApi");
  const api = new RestApi();
  const sent: { url: string; method?: string; data: unknown } = {
    url: "",
    data: undefined,
  };
  api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
    sent.url = config.url ?? "";
    sent.method = config.method;
    sent.data = config.data;
    return {
      data: undefined,
      status: 200,
      statusText: "OK",
      headers: {},
      config,
      request: {},
    } as never;
  }) as AxiosAdapter;
  return { api, sent };
};

describe("RestApi access control", () => {
  it("loadHttpTriggerAccessControl posts the search request to the access-control endpoint", async () => {
    const { api, sent } = await recordingApi();
    const request = { offset: 0, limit: 20, filters: [] };

    await api.loadHttpTriggerAccessControl(request);

    expect(sent.url).toContain("/catalog/chains/access-control");
    expect(sent.method).toBe("post");
    expect(JSON.parse(sent.data as string)).toStrictEqual(request);
  });

  it("updateHttpTriggerAccessControl puts the role batch on the access-control endpoint", async () => {
    const { api, sent } = await recordingApi();

    await api.updateHttpTriggerAccessControl([
      { elementId: "elem-1", roles: ["reader"] },
    ]);

    expect(sent.url).toContain("/catalog/chains/access-control");
    expect(sent.method).toBe("put");
    expect(JSON.parse(sent.data as string)).toStrictEqual([
      { elementId: "elem-1", roles: ["reader"] },
    ]);
  });

  it("bulkDeployChainsAccessControl puts a bare list of chain ids", async () => {
    const { api, sent } = await recordingApi();

    await api.bulkDeployChainsAccessControl(["chain-1", "chain-2"]);

    expect(sent.url).toContain("/catalog/chains/access-control/redeploy");
    expect(sent.method).toBe("put");
    expect(JSON.parse(sent.data as string)).toStrictEqual([
      "chain-1",
      "chain-2",
    ]);
  });
});
