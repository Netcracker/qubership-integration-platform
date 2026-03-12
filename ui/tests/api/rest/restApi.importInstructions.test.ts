import type { AxiosRequestConfig, AxiosAdapter } from "axios";
import {
  ImportEntityType,
  ImportInstructionAction,
} from "../../../src/api/apiTypes";

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

describe("RestApi import instructions", () => {
  it("getImportInstructions calls catalog and variables endpoints", async () => {
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      const url = config.url ?? "";
      if (url.includes("systems-catalog")) {
        return {
          data: { chains: {}, services: {} },
          status: 200,
          statusText: "OK",
          headers: {},
          config,
          request: {},
        } as never;
      }
      if (url.includes("variables-management")) {
        return {
          data: { ignore: [], delete: [] },
          status: 200,
          statusText: "OK",
          headers: {},
          config,
          request: {},
        } as never;
      }
      return {
        data: {},
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    const result = await api.getImportInstructions();
    expect(result).toBeDefined();
    expect(result.chains).toBeDefined();
    expect(result.commonVariables).toBeDefined();
  });

  it("addImportInstruction sends POST to catalog for CHAIN", async () => {
    let lastUrl = "";
    let lastData: unknown = null;
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      lastData = config.data;
      return {
        data: {},
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.addImportInstruction({
      id: "chain-1",
      entityType: ImportEntityType.CHAIN,
      action: ImportInstructionAction.IGNORE,
    });

    expect(lastUrl).toContain("/systems-catalog/import-instructions");
    const parsed =
      typeof lastData === "string" ? JSON.parse(lastData) : lastData;
    expect(parsed).toEqual({
      id: "chain-1",
      entityType: ImportEntityType.CHAIN,
      action: ImportInstructionAction.IGNORE,
    });
  });

  it("updateImportInstruction sends PATCH to catalog for CHAIN", async () => {
    let lastUrl = "";
    let lastData: unknown = null;
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      lastData = config.data;
      return {
        data: {},
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.updateImportInstruction({
      id: "chain-1",
      entityType: ImportEntityType.CHAIN,
      action: ImportInstructionAction.OVERRIDE,
      overriddenBy: "other-chain",
    });

    expect(lastUrl).toContain("/systems-catalog/import-instructions");
    const parsed =
      typeof lastData === "string" ? JSON.parse(lastData) : lastData;
    expect(parsed).toMatchObject({
      id: "chain-1",
      entityType: ImportEntityType.CHAIN,
      action: ImportInstructionAction.OVERRIDE,
      overriddenBy: "other-chain",
    });
  });

  it("addImportInstruction sends POST to variables for COMMON_VARIABLE", async () => {
    let lastUrl = "";
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      return {
        data: {},
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.addImportInstruction({
      id: "var-1",
      entityType: ImportEntityType.COMMON_VARIABLE,
      action: ImportInstructionAction.IGNORE,
    });

    expect(lastUrl).toContain(
      "/variables-management/common-variables/import-instructions",
    );
  });

  it("deleteImportInstructions sends DELETE with correct body", async () => {
    let lastUrl = "";
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      return {
        data: undefined,
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.deleteImportInstructions({
      chains: ["c1"],
      services: ["s1"],
      commonVariables: ["v1"],
    });

    expect(lastUrl).toContain("import-instructions");
  });

  it("uploadImportInstructions sends POST with FormData and multipart content-type", async () => {
    let lastUrl = "";
    let lastHeaders: Record<string, unknown> | undefined;
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      lastHeaders = config.headers as Record<string, unknown> | undefined;
      return {
        data: [],
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    const file = new File(["yaml"], "instructions.yaml", { type: "text/yaml" });
    await api.uploadImportInstructions(file);

    expect(lastUrl).toContain("/catalog/import-instructions/upload");
    expect(lastHeaders).toBeDefined();
    expect(lastHeaders?.["Content-Type"]).toContain("multipart/form-data");
  });

  it("deleteImportInstructions only chains — calls systems-catalog DELETE", async () => {
    const urls: string[] = [];
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      urls.push(config.url ?? "");
      return {
        data: undefined,
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.deleteImportInstructions({
      chains: ["c1"],
      services: [],
      commonVariables: [],
    });

    expect(urls).toHaveLength(1);
    expect(urls[0]).toContain("/systems-catalog/import-instructions");
  });

  it("deleteImportInstructions only commonVariables — calls variables DELETE", async () => {
    const urls: string[] = [];
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      urls.push(config.url ?? "");
      return {
        data: undefined,
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.deleteImportInstructions({
      chains: [],
      services: [],
      commonVariables: ["v1"],
    });

    expect(urls).toHaveLength(1);
    expect(urls[0]).toContain(
      "/variables-management/common-variables/import-instructions",
    );
  });

  it("updateImportInstruction sends PATCH to variables for COMMON_VARIABLE", async () => {
    let lastUrl = "";
    let lastData: unknown = null;
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      lastUrl = config.url ?? "";
      lastData = config.data;
      return {
        data: {},
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    await api.updateImportInstruction({
      id: "var-1",
      entityType: ImportEntityType.COMMON_VARIABLE,
      action: ImportInstructionAction.IGNORE,
    });

    expect(lastUrl).toContain(
      "/variables-management/common-variables/import-instructions",
    );
    const parsed =
      typeof lastData === "string" ? JSON.parse(lastData) : lastData;
    expect(parsed).toMatchObject({
      id: "var-1",
      entityType: ImportEntityType.COMMON_VARIABLE,
      action: ImportInstructionAction.IGNORE,
    });
  });

  it("getImportInstructions extracts commonVariables from wrapped response", async () => {
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      const url = config.url ?? "";
      if (url.includes("systems-catalog")) {
        return {
          data: {
            chains: { delete: [], ignore: [], override: [] },
            services: { delete: [], ignore: [] },
            specificationGroups: { delete: [], ignore: [] },
            specifications: { delete: [], ignore: [] },
          },
          status: 200,
          statusText: "OK",
          headers: {},
          config,
          request: {},
        } as never;
      }
      return {
        data: { commonVariables: { ignore: [{ id: "v1" }], delete: [] } },
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      } as never;
    }) as AxiosAdapter;

    const result = await api.getImportInstructions();
    expect(result.commonVariables.ignore).toEqual([{ id: "v1" }]);
    expect(result.commonVariables.delete).toEqual([]);
  });

  it("exportImportInstructions sends GET and returns File", async () => {
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = (async (config: AxiosRequestConfig) => {
      const blob = new Blob(["content"]);
      return {
        data: blob,
        status: 200,
        statusText: "OK",
        headers: {
          "content-disposition":
            'attachment; filename="import-instructions.yaml"',
        },
        config: {
          ...config,
          headers: config.headers ?? {},
        } as AxiosRequestConfig,
        request: {},
      } as never;
    }) as AxiosAdapter;

    const result = await api.exportImportInstructions();
    expect(result).toBeInstanceOf(File);
    expect(result.name).toBe("import-instructions.yaml");
  });
});
