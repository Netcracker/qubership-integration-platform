import type { AxiosRequestConfig, AxiosAdapter } from "axios";
import { installRequestHeaders } from "../../../src/api/rest/requestHeadersInterceptor";

// Make axios-rate-limit a pass-through for deterministic tests.
jest.mock("axios-rate-limit", () => ({
  __esModule: true,
  default: (instance: unknown) => instance,
}));

// Mock app config to avoid relying on import.meta in Jest runtime.
jest.mock("../../../src/appConfig", () => ({
  getConfig: () => ({ apiGateway: "https://api.example.com" }),
}));

describe("RestApi variables-management", () => {
  it("sends variables-management requests under /api/v1/cip", async () => {
    const { RestApi } = await import("../../../src/api/rest/restApi");

    let lastUrl: string | undefined;
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url;
      return {
        data: {},
        status: 200,
        statusText: "OK",
        headers: {},
        config: {
          ...config,
          headers: config.headers ?? {},
        } as AxiosRequestConfig,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.getCommonVariables();
    expect(lastUrl).toContain("/api/v1/cip/variables-management");
  });

  it("applies installRequestHeaders() to variables-management requests", async () => {
    const { RestApi } = await import("../../../src/api/rest/restApi");

    let lastHeaders: Record<string, unknown> | undefined;
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastHeaders = config.headers;
      return {
        data: {},
        status: 200,
        statusText: "OK",
        headers: {},
        config: {
          ...config,
          headers: config.headers ?? {},
        } as AxiosRequestConfig,
        request: {},
      } as never;
    }) as AxiosAdapter;

    const handle = installRequestHeaders(() => ({ "X-Test": "1" }));
    await api.getCommonVariables();
    expect(lastHeaders?.["X-Test"]).toBe("1");
    handle.eject();
  });

  it("maps backend ErrorResponse into ApiResponse.error (no throw)", async () => {
    const { RestApi } = await import("../../../src/api/rest/restApi");

    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      // emulate axios rejecting on non-2xx
      return Promise.reject({
        isAxiosError: true,
        response: {
          data: {
            serviceName: "variables-management",
            errorMessage: "Bad request",
            errorDate: "2020-01-01T00:00:00.000Z",
          },
          status: 400,
          statusText: "Bad Request",
          headers: {},
          config: { ...config, headers: config.headers ?? {} },
          request: {},
        },
        config,
      });
    }) as AxiosAdapter;

    const result = await api.getCommonVariables();
    expect(result.success).toBe(false);
    expect(result.error?.responseBody.serviceName).toBe("variables-management");
    expect(result.error?.responseBody.errorMessage).toBe("Bad request");
    expect(result.error?.responseBody.errorDate).toBe(
      "2020-01-01T00:00:00.000Z",
    );
  });
});
