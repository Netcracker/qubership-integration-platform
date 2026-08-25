/**
 * @jest-environment jsdom
 */

import type { AxiosAdapter, AxiosRequestConfig } from "axios";
import {
  MatcherEntityType,
  MatcherType,
  TestingFilterCondition,
  TestingSortOrder,
  TestsRunSource,
} from "../../../src/api/apiTypes";
import type {
  TestCaseRequest,
  TestingSelectionSpecification,
} from "../../../src/api/apiTypes";
import type { RestApi } from "../../../src/api/rest/restApi";

jest.mock("axios-rate-limit", () => ({
  __esModule: true,
  default: (instance: unknown) => instance,
}));

jest.mock("../../../src/appConfig", () => ({
  getAppName: () => "qip",
  getConfig: () => ({ apiGateway: "https://api.example.com" }),
}));

jest.mock("../../../src/api/rest/requestHeadersInterceptor", () => ({
  registerRestAxiosInstance: jest.fn(),
}));

type RecordedRequest = {
  method?: string;
  url?: string;
  params?: Record<string, unknown>;
  data?: unknown;
};

const testingBase = "/api/v1/qip/testing-service";

async function createApi(
  responseData: unknown = [],
  responseHeaders: Record<string, string> = {},
): Promise<{ api: RestApi; requests: RecordedRequest[] }> {
  const { RestApi } = await import("../../../src/api/rest/restApi");
  const api = new RestApi();
  const requests: RecordedRequest[] = [];
  api.instance.defaults.adapter = ((config: AxiosRequestConfig) => {
    requests.push({
      method: config.method,
      url: config.url,
      params: config.params as Record<string, unknown>,
      data: config.data,
    });
    return Promise.resolve({
      data: responseData,
      status: 200,
      statusText: "OK",
      headers: responseHeaders,
      config,
      request: {},
    } as never);
  }) as AxiosAdapter;
  return { api, requests };
}

function lastRequest(requests: RecordedRequest[]): RecordedRequest {
  return requests[requests.length - 1];
}

function sentBody(request: RecordedRequest): unknown {
  return JSON.parse(request.data as string);
}

const specification: TestingSelectionSpecification = {
  searchText: "order",
  filters: [
    {
      feature: "chain_id",
      condition: TestingFilterCondition.IN,
      values: ["chain-1", "chain-2"],
    },
  ],
};

describe("RestApi testing service", () => {
  it("should send the selection specification as the list body when listing test cases", async () => {
    const { api, requests } = await createApi();

    await api.getTestCases(specification);

    const request = lastRequest(requests);
    expect(request.method).toBe("post");
    expect(request.url).toBe(`${testingBase}/test-cases`);
    expect(sentBody(request)).toEqual(specification);
  });

  it("should send the offset alone when paginating, because the page size is server-controlled", async () => {
    const { api, requests } = await createApi();

    await api.getTestCases(specification, { offset: 40 });

    expect(lastRequest(requests).params).toEqual({ offset: "40" });
  });

  it("should send no pagination parameters when the offset is the first page", async () => {
    const { api, requests } = await createApi();

    await api.getTestCases(specification, { offset: 0 });

    expect(lastRequest(requests).params).toEqual({});
  });

  it("should send the sort field with its order when sorting", async () => {
    const { api, requests } = await createApi();

    await api.getTestCaseRuns(specification, {
      sortBy: "start",
      sortOrder: TestingSortOrder.DESC,
    });

    const request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/test-case-runs`);
    expect(request.params).toEqual({ sort_by: "start", sort_order: "DESC" });
  });

  it("should default the sort order to ascending when only a field is given", async () => {
    const { api, requests } = await createApi();

    await api.getTestsRuns(specification, { sortBy: "id" });

    expect(lastRequest(requests).params).toEqual({
      sort_by: "id",
      sort_order: "ASC",
    });
  });

  it("should ask for ids only and drop pagination when resolving a selection", async () => {
    const { api, requests } = await createApi(["case-1", "case-2"]);

    const ids = await api.getTestCaseIds(specification);

    const request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/test-cases`);
    expect(request.params).toEqual({ return_ids: true });
    expect(sentBody(request)).toEqual(specification);
    expect(ids).toEqual(["case-1", "case-2"]);
  });

  it("should resolve ids of every listed entity", async () => {
    const { api, requests } = await createApi([]);

    await api.getEndpointMockIds(specification);
    expect(lastRequest(requests).url).toBe(`${testingBase}/endpoint-mocks`);

    await api.getTestsRunIds(specification);
    expect(lastRequest(requests).url).toBe(`${testingBase}/tests-runs`);

    await api.getTestCaseRunIds(specification);
    expect(lastRequest(requests).url).toBe(`${testingBase}/test-case-runs`);
  });

  it("should read one entity by id", async () => {
    const { api, requests } = await createApi({});

    await api.getTestCase("case-1");
    expect(lastRequest(requests).url).toBe(`${testingBase}/test-cases/case-1`);

    await api.getEndpointMock("mock-1");
    expect(lastRequest(requests).url).toBe(
      `${testingBase}/endpoint-mocks/mock-1`,
    );

    await api.getTestCaseRun("case-run-1");
    expect(lastRequest(requests).url).toBe(
      `${testingBase}/test-case-runs/case-run-1`,
    );
  });

  it("should post to the create path when creating and to the id path when updating", async () => {
    const { api, requests } = await createApi({});
    const testCase: TestCaseRequest = {
      name: "order flow",
      description: "",
      enabled: false,
      triggerReference: { chainId: "chain-1", elementId: "element-1" },
      requestSettings: {
        queryParameters: null,
        pathParameters: null,
        message: null,
        method: "GET",
        timeout: 120000,
      },
      responseValidationRules: [
        {
          name: "status is 200",
          description: "",
          enabled: true,
          type: MatcherType.EQUAL,
          entityType: MatcherEntityType.STATUS,
          entityName: null,
          parameters: [{ name: "value", value: "200" }],
        },
      ],
    };

    await api.createTestCase(testCase);
    let request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/test-cases/create`);
    expect(sentBody(request)).toEqual(testCase);

    await api.updateTestCase("case-1", testCase);
    request = lastRequest(requests);
    expect(request.method).toBe("post");
    expect(request.url).toBe(`${testingBase}/test-cases/case-1`);
    expect(sentBody(request)).toEqual(testCase);
  });

  it("should carry the ids in the body when deleting", async () => {
    const { api, requests } = await createApi();

    await api.deleteTestCases(["case-1", "case-2"]);

    const request = lastRequest(requests);
    expect(request.method).toBe("delete");
    expect(request.url).toBe(`${testingBase}/test-cases`);
    expect(sentBody(request)).toEqual(["case-1", "case-2"]);
  });

  it("should delete mocks and run sets through the same bulk path", async () => {
    const { api, requests } = await createApi();

    await api.deleteEndpointMocks(["mock-1"]);
    expect(lastRequest(requests).url).toBe(`${testingBase}/endpoint-mocks`);

    await api.deleteTestsRuns(["run-1"]);
    const request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/tests-runs`);
    expect(sentBody(request)).toEqual(["run-1"]);
  });

  it("should post the ids to the bulk cancel paths", async () => {
    const { api, requests } = await createApi();

    await api.cancelTestsRuns(["run-1"]);
    let request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/tests-runs/cancel`);
    expect(sentBody(request)).toEqual(["run-1"]);

    await api.cancelTestCaseRuns(["case-run-1"]);
    request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/test-case-runs/cancel`);
    expect(sentBody(request)).toEqual(["case-run-1"]);
  });

  it("should upload every file under a repeated field when importing", async () => {
    const { api, requests } = await createApi([]);
    const files = [
      new File(["a"], "cases.zip"),
      new File(["b"], "more-cases.zip"),
    ];

    await api.importTestCases(files);

    const request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/test-cases/import`);
    const formData = request.data as FormData;
    expect(formData).toBeInstanceOf(FormData);
    expect(
      formData.getAll("file").map((entry) => (entry as File).name),
    ).toEqual(["cases.zip", "more-cases.zip"]);
  });

  it("should import mocks through their own path", async () => {
    const { api, requests } = await createApi([]);

    await api.importEndpointMocks([new File(["a"], "mocks.zip")]);

    expect(lastRequest(requests).url).toBe(
      `${testingBase}/endpoint-mocks/import`,
    );
  });

  // The service answers an export with the payload and its content type alone, so
  // no header names the file. Every case below sends none, as the service does.
  it("should name the exported file itself and keep the content type", async () => {
    const { api, requests } = await createApi(
      new Blob(["id,name"], { type: "text/csv" }),
      {},
    );

    const file = await api.exportTestsRuns(["run-1"]);

    const request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/tests-runs/export`);
    expect(sentBody(request)).toEqual(["run-1"]);
    expect(file.name).toBe("tests-runs.csv");
    expect(file.type).toBe("text/csv");
  });

  it("should export every entity through its own path under its own name", async () => {
    const { api, requests } = await createApi(new Blob([""]), {});

    expect((await api.exportTestCases(["case-1"])).name).toBe("test-cases.zip");
    expect(lastRequest(requests).url).toBe(`${testingBase}/test-cases/export`);

    expect((await api.exportEndpointMocks(["mock-1"])).name).toBe(
      "endpoint-mocks.zip",
    );
    expect(lastRequest(requests).url).toBe(
      `${testingBase}/endpoint-mocks/export`,
    );

    expect((await api.exportTestCaseRuns(["case-run-1"])).name).toBe(
      "test-case-runs.csv",
    );
    expect(lastRequest(requests).url).toBe(
      `${testingBase}/test-case-runs/export`,
    );

    expect((await api.exportTestCaseRunErrors(["error-1"])).name).toBe(
      "validation-errors.csv",
    );
    expect(lastRequest(requests).url).toBe(
      `${testingBase}/test-case-runs/errors/export`,
    );
  });

  it("should prefer a content-disposition name when the response carries one", async () => {
    const { api } = await createApi(new Blob([""], { type: "text/csv" }), {
      "content-disposition": 'attachment; filename="runs.csv"',
    });

    expect((await api.exportTestsRuns(["run-1"])).name).toBe("runs.csv");
  });

  it("should omit the source parameter when a run starts from test cases", async () => {
    const { api, requests } = await createApi("run-1");

    const runId = await api.startTestsRun(["case-1", "case-2"]);

    const request = lastRequest(requests);
    expect(request.url).toBe(`${testingBase}/tests-runs/create`);
    expect(request.params).toBeUndefined();
    expect(sentBody(request)).toEqual(["case-1", "case-2"]);
    expect(runId).toBe("run-1");
  });

  it("should name the source when a run restarts from run sets or case runs", async () => {
    const { api, requests } = await createApi("run-2");

    await api.startTestsRun(["run-1"], TestsRunSource.TESTS_RUNS);
    expect(lastRequest(requests).params).toEqual({ from: "tests_runs" });

    await api.startTestsRun(["case-run-1"], TestsRunSource.TEST_CASE_RUNS);
    expect(lastRequest(requests).params).toEqual({ from: "test_case_runs" });
  });

  it("should always ask run errors for their matchers", async () => {
    const { api, requests } = await createApi([]);

    await api.getTestCaseRunErrors("case-run-1");

    const request = lastRequest(requests);
    expect(request.method).toBe("get");
    expect(request.url).toBe(`${testingBase}/test-case-runs/case-run-1/errors`);
    expect(request.params).toEqual({ withMatchers: true });
  });

  it("should read the service mode from the testing service", async () => {
    const { api, requests } = await createApi({ production: true });

    const mode = await api.getTestingServiceMode();

    expect(lastRequest(requests).url).toBe(`${testingBase}/mode`);
    expect(mode.production).toBe(true);
  });

  it("should look the session of a case run up by its external id", async () => {
    const { api, requests } = await createApi({ id: "session-1" });

    await api.getSessionByExternalId("external-1");

    expect(lastRequest(requests).url).toBe(
      "/api/v1/qip/sessions-management/sessions/external-id/external-1",
    );
  });

  it("should reject when the service answers with a failure", async () => {
    const { RestApi } = await import("../../../src/api/rest/restApi");
    const api = new RestApi();
    api.instance.defaults.adapter = ((config: AxiosRequestConfig) =>
      Promise.reject(
        Object.assign(new Error("Request failed"), {
          isAxiosError: true,
          config,
          response: {
            data: {
              serviceName: "testing-service",
              errorMessage: "Malformed selection parameters",
              errorDate: "2026-08-13 10:00:00.000",
            },
            status: 400,
            statusText: "Bad Request",
            headers: {},
            config,
          },
        }),
      )) as AxiosAdapter;

    await expect(api.getTestCases(specification)).rejects.toThrow(
      "Malformed selection parameters",
    );
  });
});
