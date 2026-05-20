/**
 * @jest-environment jsdom
 */

import { describe, it, expect, beforeEach } from "@jest/globals";

const mockPost = jest.fn();
const mockGet = jest.fn();

jest.mock("axios", () => {
  const mockInstance = {
    post: (...args: unknown[]) => mockPost(...args),
    get: (...args: unknown[]) => mockGet(...args),
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
import type { EntityFilterModel } from "../../../src/components/table/filter/filter";

describe("RestApi - filterServices and searchServices", () => {
  let restApi: RestApi;

  beforeEach(() => {
    jest.clearAllMocks();
    restApi = new RestApi();
  });

  it("filterServices sends POST with filter body", async () => {
    const filters: EntityFilterModel[] = [
      { column: "NAME", condition: "CONTAINS", value: "test" },
      { column: "PROTOCOL", condition: "IN", value: "HTTP" },
    ];
    mockPost.mockResolvedValue({ data: [] });

    await restApi.filterServices(filters);

    expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/systems/filter"),
      [
        { column: "NAME", condition: "CONTAINS", value: "test" },
        { column: "PROTOCOL", condition: "IN", value: "HTTP" },
      ],
    );
  });

  it("searchServices sends POST with searchCondition", async () => {
    mockPost.mockResolvedValue({ data: [] });

    await restApi.searchServices("my query");

    expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/systems/search"),
      { searchCondition: "my query" },
    );
  });

  it("filterServices returns response data", async () => {
    const mockData = [{ id: "1", name: "Test" }];
    mockPost.mockResolvedValue({ data: mockData });

    const result = await restApi.filterServices([]);
    expect(result).toEqual(mockData);
  });

  it("searchServices returns response data", async () => {
    const mockData = [{ id: "2", name: "Found" }];
    mockPost.mockResolvedValue({ data: mockData });

    const result = await restApi.searchServices("found");
    expect(result).toEqual(mockData);
  });
});
