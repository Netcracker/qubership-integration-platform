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
import type { EntityFilterModel } from "../../../src/components/table/filter/filterTypes";
import type { CheckpointSession } from "../../../src/api/apiTypes";

describe("RestApi - filterServices and searchServices", () => {
  let restApi: RestApi;

  beforeEach(() => {
    jest.clearAllMocks();
    restApi = new RestApi();
  });

  it("getServices sends GET without chain usage by default", async () => {
    mockGet.mockResolvedValue({ data: [] });

    await restApi.getServices("all", false);

    expect(mockGet).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/systems"),
      {
        params: { modelType: "all", withSpec: false, includeChainUsage: false },
      },
    );
  });

  it("getServices asks for chain usage when requested", async () => {
    mockGet.mockResolvedValue({ data: [] });

    await restApi.getServices("all", false, true);

    expect(mockGet).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/systems"),
      {
        params: { modelType: "all", withSpec: false, includeChainUsage: true },
      },
    );
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
      { params: { includeChainUsage: false } },
    );
  });

  it("filterServices asks for chain usage when requested", async () => {
    mockPost.mockResolvedValue({ data: [] });

    await restApi.filterServices([], true);

    expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/systems/filter"),
      [],
      { params: { includeChainUsage: true } },
    );
  });

  it("searchServices sends POST with searchCondition", async () => {
    mockPost.mockResolvedValue({ data: [] });

    await restApi.searchServices("my query");

    expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/systems/search"),
      { searchCondition: "my query" },
      { params: { includeChainUsage: false } },
    );
  });

  it("searchServices asks for chain usage when requested", async () => {
    mockPost.mockResolvedValue({ data: [] });

    await restApi.searchServices("my query", true);

    expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining("/systems-catalog/systems/search"),
      { searchCondition: "my query" },
      { params: { includeChainUsage: true } },
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

describe("RestApi - checkpoint sessions for micro-domain", () => {
  let restApi: RestApi;

  beforeEach(() => {
    jest.clearAllMocks();
    restApi = new RestApi();
  });

  it("should return an empty array without calling GET when sessionIds is empty", async () => {
    const result = await restApi.getCheckpointSessionsForMicroDomain(
      "domain-1",
      [],
    );

    expect(result).toEqual([]);
    expect(mockGet).not.toHaveBeenCalled();
  });

  it("should call GET on the domain-scoped sessions endpoint with the ids as params", async () => {
    const mockData = [{ id: "session-1" }] as unknown as CheckpointSession[];
    mockGet.mockResolvedValue({ data: mockData });

    const result = await restApi.getCheckpointSessionsForMicroDomain(
      "domain-1",
      ["session-1"],
    );

    expect(mockGet).toHaveBeenCalledWith(
      expect.stringContaining("/engine/domain-1/sessions"),
      {
        params: { ids: ["session-1"] },
        paramsSerializer: { indexes: null },
      },
    );
    expect(result).toEqual(mockData);
  });

  it("should split session ids into chunks of 20 and merge the results across chunk requests", async () => {
    const sessionIds = Array.from({ length: 25 }, (_, i) => `session-${i}`);
    mockGet
      .mockResolvedValueOnce({ data: [{ id: "session-0" }] })
      .mockResolvedValueOnce({ data: [{ id: "session-20" }] });

    const result = await restApi.getCheckpointSessionsForMicroDomain(
      "domain-1",
      sessionIds,
    );

    expect(mockGet).toHaveBeenCalledTimes(2);
    expect(mockGet).toHaveBeenNthCalledWith(
      1,
      expect.stringContaining("/engine/domain-1/sessions"),
      expect.objectContaining({ params: { ids: sessionIds.slice(0, 20) } }),
    );
    expect(mockGet).toHaveBeenNthCalledWith(
      2,
      expect.stringContaining("/engine/domain-1/sessions"),
      expect.objectContaining({ params: { ids: sessionIds.slice(20) } }),
    );
    expect(result).toEqual([{ id: "session-0" }, { id: "session-20" }]);
  });

  it("should send a POST to the domain-scoped retry endpoint with an empty body", async () => {
    mockPost.mockResolvedValue({});

    await restApi.retrySessionFromCheckpointForMicroDomain(
      "domain-1",
      "chain-1",
      "session-1",
    );

    expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining(
        "/engine/domain-1/chains/chain-1/sessions/session-1/retry",
      ),
      {},
    );
  });
});
