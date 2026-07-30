/**
 * @jest-environment jsdom
 */
import { describe, it, expect, beforeEach } from "@jest/globals";

jest.mock("../../../src/appConfig", () => ({
  getConfig: () => ({ apiGateway: "http://localhost", appName: "test" }),
  getAppName: () => "test",
}));

import { VSCodeExtensionApi } from "../../../src/api/rest/vscodeExtensionApi";

/**
 * The API group and API methods are a thin transport over `sendMessageToExtension`, so the contract
 * worth pinning is the message type and payload each one puts on the wire: the extension host
 * dispatches on exactly those strings.
 */
describe("VSCodeExtensionApi - API groups and APIs", () => {
  let api: VSCodeExtensionApi;
  let sent: jest.Mock;

  beforeEach(() => {
    (globalThis as unknown as Record<string, unknown>).acquireVsCodeApi =
      () => ({
        postMessage: jest.fn(),
        getState: jest.fn(),
        setState: jest.fn(),
      });
    api = new VSCodeExtensionApi();
    sent = jest.fn().mockResolvedValue({ payload: undefined });
    api.sendMessageToExtension = sent;
  });

  it("should ask the extension for the API groups of a service", async () => {
    sent.mockResolvedValue({ payload: [{ id: "g1" }] });

    const groups = await api.getApiSpecifications("s1");

    expect(sent).toHaveBeenCalledWith("getApiSpecifications", "s1");
    expect(groups).toEqual([{ id: "g1" }]);
  });

  it("should ask the extension for the latest API of a service", async () => {
    sent.mockResolvedValue({ payload: { id: "m1" } });

    const latest = await api.getLatestApiSpecification("s1");

    expect(sent).toHaveBeenCalledWith("getLatestApiSpecification", "s1");
    expect(latest).toEqual({ id: "m1" });
  });

  it("should send the group id and body separately when updating an API group", async () => {
    sent.mockResolvedValue({ payload: { id: "g1", name: "renamed" } });

    const group = await api.updateApiSpecificationGroup("g1", {
      name: "renamed",
    });

    expect(sent).toHaveBeenCalledWith("updateApiSpecificationGroup", {
      id: "g1",
      group: { name: "renamed" },
    });
    expect(group).toEqual({ id: "g1", name: "renamed" });
  });

  it("should delete an API group by id", async () => {
    await api.deleteSpecificationGroup("g1");

    expect(sent).toHaveBeenCalledWith("deleteSpecificationGroup", "g1");
  });

  it("should name the service and group fields when listing APIs", async () => {
    sent.mockResolvedValue({ payload: [{ id: "m1" }] });

    const apis = await api.getSpecificationModel("s1", "g1");

    expect(sent).toHaveBeenCalledWith("getSpecificationModel", {
      serviceId: "s1",
      groupId: "g1",
    });
    expect(apis).toEqual([{ id: "m1" }]);
  });

  it("should request the raw source of an API", async () => {
    sent.mockResolvedValue({ payload: "openapi: 3.0.0" });

    const source = await api.getSpecificationModelSource("m1");

    expect(sent).toHaveBeenCalledWith("getSpecificationModelSource", "m1");
    expect(source).toBe("openapi: 3.0.0");
  });

  it("should send the API id and body separately when updating an API", async () => {
    sent.mockResolvedValue({ payload: { id: "m1", name: "renamed" } });

    const updated = await api.updateSpecificationModel("m1", {
      name: "renamed",
    });

    expect(sent).toHaveBeenCalledWith("updateSpecificationModel", {
      id: "m1",
      model: { name: "renamed" },
    });
    expect(updated).toEqual({ id: "m1", name: "renamed" });
  });

  it("should delete an API by id", async () => {
    await api.deleteSpecificationModel("m1");

    expect(sent).toHaveBeenCalledWith("deleteSpecificationModel", "m1");
  });

  it("should deprecate an API by id", async () => {
    sent.mockResolvedValue({ payload: { id: "m1", deprecated: true } });

    const deprecated = await api.deprecateModel("m1");

    expect(sent).toHaveBeenCalledWith("deprecateModel", "m1");
    expect(deprecated).toEqual({ id: "m1", deprecated: true });
  });
});
