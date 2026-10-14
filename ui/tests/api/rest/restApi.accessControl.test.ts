/**
 * @jest-environment jsdom
 */
import type { AxiosRequestConfig, AxiosAdapter } from "axios";

jest.mock("axios-rate-limit", () => ({
  __esModule: true,
  default: (instance: unknown) => instance,
}));

jest.mock("../../../src/appConfig", () => ({
  getAppName: () => "cip",
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
  it("updateHttpTriggerAccessControl puts the role batch on the roles endpoint", async () => {
    const { api, sent } = await recordingApi();

    await api.updateHttpTriggerAccessControl([
      { elementId: "elem-1", roles: ["reader"] },
    ]);

    expect(sent.url).toContain("/catalog/chains/roles");
    expect(sent.method).toBe("put");
    expect(JSON.parse(sent.data as string)).toStrictEqual([
      { elementId: "elem-1", roles: ["reader"] },
    ]);
  });

  it("bulkDeployChainsAccessControl puts a bare list of chain ids", async () => {
    const { api, sent } = await recordingApi();

    await api.bulkDeployChainsAccessControl(["chain-1", "chain-2"]);

    expect(sent.url).toContain("/catalog/chains/roles/redeploy");
    expect(sent.method).toBe("put");
    expect(JSON.parse(sent.data as string)).toStrictEqual([
      "chain-1",
      "chain-2",
    ]);
  });
});
