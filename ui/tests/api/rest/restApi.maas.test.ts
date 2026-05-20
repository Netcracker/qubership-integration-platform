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

describe("RestApi MaaS", () => {
  it("createMaasKafkaEntity sends POST with namespace and topicClassifierName params", async () => {
    let lastUrl = "";
    let lastParams: Record<string, string> | undefined;
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      lastParams = config.params as Record<string, string> | undefined;
      return {
        data: undefined,
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.createMaasKafkaEntity({
      namespace: "my-namespace",
      topicClassifierName: "my-classifier",
    });

    expect(lastUrl).toContain("/maas-actions/kafka");
    expect(lastParams).toEqual({
      namespace: "my-namespace",
      topicClassifierName: "my-classifier",
    });
  });

  it("createMaasRabbitMQEntity sends POST with all params", async () => {
    let lastUrl = "";
    let lastParams: Record<string, string | undefined> | undefined;
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      lastParams = config.params as
        | Record<string, string | undefined>
        | undefined;
      return {
        data: undefined,
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.createMaasRabbitMQEntity({
      namespace: "ns",
      vhost: "public",
      exchange: "ex",
      queue: "q",
      routingKey: "routing.key",
    });

    expect(lastUrl).toContain("/maas-actions/rabbitmq");
    expect(lastParams).toEqual({
      namespace: "ns",
      vhost: "public",
      exchange: "ex",
      queue: "q",
      routingKey: "routing.key",
    });
  });

  it("getMaasKafkaDeclarativeFile sends POST and returns File", async () => {
    let lastUrl = "";
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      const blob = new Blob(["declarative json"]);
      return {
        data: blob,
        status: 200,
        statusText: "OK",
        headers: {
          "content-disposition":
            'attachment; filename="kafka-declarative.json"',
        },
        config: {
          ...config,
          headers: config.headers ?? {},
        } as AxiosRequestConfig,
        request: {},
      } as never;
    }) as AxiosAdapter;

    const result = await api.getMaasKafkaDeclarativeFile({
      topicClassifierName: "my-classifier",
    });

    expect(lastUrl).toContain("/maas-actions/kafka/declarative");
    expect(result).toBeInstanceOf(File);
    expect(result.name).toBe("kafka-declarative.json");
  });

  it("getMaasRabbitMQDeclarativeFile sends POST with params and returns File", async () => {
    let lastUrl = "";
    let lastParams: Record<string, string | undefined> | undefined;
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      lastParams = config.params as
        | Record<string, string | undefined>
        | undefined;
      const blob = new Blob(["declarative json"]);
      return {
        data: blob,
        status: 200,
        statusText: "OK",
        headers: {
          "content-disposition":
            'attachment; filename="rabbitmq-declarative.json"',
        },
        config: {
          ...config,
          headers: config.headers ?? {},
        } as AxiosRequestConfig,
        request: {},
      } as never;
    }) as AxiosAdapter;

    const result = await api.getMaasRabbitMQDeclarativeFile({
      vhost: "public",
      exchange: "ex",
      queue: "q",
      routingKey: "routing.key",
    });

    expect(lastUrl).toContain("/maas-actions/rabbitmq/declarative");
    expect(lastParams).toEqual({
      vhost: "public",
      exchange: "ex",
      queue: "q",
      routingKey: "routing.key",
    });
    expect(result).toBeInstanceOf(File);
    expect(result.name).toBe("rabbitmq-declarative.json");
  });
});
