/**
 * @jest-environment jsdom
 */
import { describe, it, expect, beforeEach } from "@jest/globals";

const mockGet = jest.fn();
const mockPost = jest.fn();
const mockPatch = jest.fn();
const mockDelete = jest.fn();

jest.mock("axios", () => {
  const mockInstance = {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
    patch: (...args: unknown[]) => mockPatch(...args),
    delete: (...args: unknown[]) => mockDelete(...args),
    defaults: { baseURL: "" },
    interceptors: {
      request: { use: jest.fn() },
      response: { use: jest.fn() },
    },
  };
  return {
    __esModule: true,
    default: {
      create: jest.fn(() => mockInstance),
      isAxiosError: jest.fn(),
    },
  };
});

jest.mock("axios-rate-limit", () => ({
  __esModule: true,
  default: (instance: unknown) => instance,
}));

jest.mock("../../../src/appConfig", () => ({
  getConfig: () => ({ apiGateway: "http://localhost", appName: "test" }),
  getAppName: () => "test",
}));

import { RestApi } from "../../../src/api/rest/restApi";

describe("RestApi - API groups and APIs", () => {
  let restApi: RestApi;

  beforeEach(() => {
    jest.clearAllMocks();
    restApi = new RestApi();
  });

  it("should request API groups of a service by system id", async () => {
    mockGet.mockResolvedValue({ data: [{ id: "g1" }] });

    const groups = await restApi.getApiSpecifications("s1");

    expect(mockGet).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/specificationGroups"),
      { params: { systemId: "s1" } },
    );
    expect(groups).toEqual([{ id: "g1" }]);
  });

  it("should request the latest API of a service", async () => {
    mockGet.mockResolvedValue({ data: { id: "m1" } });

    const api = await restApi.getLatestApiSpecification("s1");

    expect(mockGet).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/models/latest"),
      { params: { systemId: "s1" } },
    );
    expect(api).toEqual({ id: "m1" });
  });

  it("should patch an API group by id", async () => {
    mockPatch.mockResolvedValue({ data: { id: "g1", name: "renamed" } });

    const group = await restApi.updateApiSpecificationGroup("g1", {
      name: "renamed",
    });

    expect(mockPatch).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/specificationGroups/g1"),
      { name: "renamed" },
    );
    expect(group).toEqual({ id: "g1", name: "renamed" });
  });

  it("should delete an API group by id", async () => {
    mockDelete.mockResolvedValue({ data: undefined });

    await restApi.deleteSpecificationGroup("g1");

    expect(mockDelete).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/specificationGroups/g1"),
    );
  });

  it("should patch an API by id", async () => {
    mockPatch.mockResolvedValue({ data: { id: "m1", name: "renamed" } });

    const api = await restApi.updateSpecificationModel("m1", {
      name: "renamed",
    });

    expect(mockPatch).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/models/m1"),
      { name: "renamed" },
    );
    expect(api).toEqual({ id: "m1", name: "renamed" });
  });

  it("should request the raw source of an API", async () => {
    mockGet.mockResolvedValue({ data: "openapi: 3.0.0" });

    const source = await restApi.getSpecificationModelSource("m1");

    expect(mockGet).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/models/m1/source"),
    );
    expect(source).toBe("openapi: 3.0.0");
  });

  it("should delete an API by id", async () => {
    mockDelete.mockResolvedValue({ data: undefined });

    await restApi.deleteSpecificationModel("m1");

    expect(mockDelete).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/models/m1"),
    );
  });

  it("should request APIs filtered by service and group", async () => {
    mockGet.mockResolvedValue({ data: [{ id: "m1" }] });

    const apis = await restApi.getSpecificationModel("s1", "g1");

    expect(mockGet).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/models"),
      { params: { systemId: "s1", specificationGroupId: "g1" } },
    );
    expect(apis).toEqual([{ id: "m1" }]);
  });

  it("should send the model id as plain text when deprecating an API", async () => {
    mockPost.mockResolvedValue({ data: { id: "m1", deprecated: true } });

    const api = await restApi.deprecateModel("m1");

    expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/models/deprecated"),
      "m1",
      { headers: { "Content-Type": "text/plain" } },
    );
    expect(api).toEqual({ id: "m1", deprecated: true });
  });
});
