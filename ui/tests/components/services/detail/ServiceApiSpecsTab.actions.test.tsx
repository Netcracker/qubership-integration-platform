/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import type { Api, ApiGroup } from "../../../../src/api/apiTypes";
import type { useNotificationService } from "../../../../src/hooks/useNotificationService";
import type { ServiceEntity } from "../../../../src/components/services/ServicesTreeTable";

const deleteSpecificationGroup = jest.fn<() => Promise<void>>();
const deleteSpecificationModel = jest.fn<() => Promise<void>>();
const deprecateModel = jest.fn<() => Promise<unknown>>();
const exportSpecifications = jest.fn<() => Promise<File>>();

jest.mock("../../../../src/api/api", () => ({
  api: {
    deleteSpecificationGroup: (...a: unknown[]) =>
      deleteSpecificationGroup(...(a as [])),
    deleteSpecificationModel: (...a: unknown[]) =>
      deleteSpecificationModel(...(a as [])),
    deprecateModel: (...a: unknown[]) => deprecateModel(...(a as [])),
    exportSpecifications: (...a: unknown[]) =>
      exportSpecifications(...(a as [])),
  },
}));

const messageSuccess = jest.fn();
const messageInfo = jest.fn();
jest.mock("../../../../src/misc/antd-app.ts", () => ({
  message: {
    success: (...a: unknown[]) => messageSuccess(...a),
    info: (...a: unknown[]) => messageInfo(...a),
    error: jest.fn(),
  },
}));

jest.mock("../../../../src/api/rest/vscodeExtensionApi.ts", () => ({
  isVsCode: false,
}));

jest.mock("../../../../src/misc/download-utils", () => ({
  downloadFile: jest.fn(),
}));

import {
  getGroupActions,
  getSpecActions,
} from "../../../../src/components/services/detail/ServiceApiSpecsTab";

const requestFailed = jest.fn();
const notify = {
  requestFailed,
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
} as unknown as ReturnType<typeof useNotificationService>;

function makeGroup(overrides: Partial<ApiGroup> = {}): ApiGroup {
  return {
    id: "g1",
    name: "Group",
    systemId: "sys-1",
    synchronization: false,
    ...overrides,
  } as ApiGroup;
}

function makeSpec(overrides: Partial<Api> = {}): Api {
  return {
    id: "m1",
    name: "API",
    specificationGroupId: "g1",
    version: "1.0",
    source: "MANUAL",
    systemId: "sys-1",
    ...overrides,
  };
}

function groupActionsFor(
  record: ServiceEntity,
  expandedRowKeys: string[] = [],
  setExpandedRowKeys: (keys: string[]) => void = jest.fn(),
  refreshGroups: () => Promise<void> = jest.fn(async () => {}),
  showModal: (modal: { component: React.ReactNode }) => void = jest.fn(),
) {
  return getGroupActions(
    expandedRowKeys,
    setExpandedRowKeys,
    refreshGroups,
    notify,
    showModal,
    "sys-1",
    false,
    jest.fn(async () => {}),
    jest.fn(async () => {}),
  )(record);
}

function specActionsFor(
  record: ServiceEntity,
  expandedRowKeys: string[] = [],
  setExpandedRowKeys: (keys: string[]) => void = jest.fn(),
  refreshModels: () => Promise<void> = jest.fn(async () => {}),
) {
  return getSpecActions(
    expandedRowKeys,
    setExpandedRowKeys,
    refreshModels,
    notify,
  )(record);
}

/**
 * The action handlers are fire-and-forget from the table's point of view: their type is
 * `(record) => void`, so the promise inside cannot be awaited directly. Draining the microtask
 * queue lets the handler's own await chain settle before the assertions run.
 */
async function flushHandler(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe("getGroupActions handlers", () => {
  it("should offer no group actions for an API row", () => {
    expect(groupActionsFor(makeSpec() as unknown as ServiceEntity)).toEqual([]);
  });

  it("should add the group key when expanding a collapsed group", () => {
    const setExpanded = jest.fn();
    const actions = groupActionsFor(makeGroup(), [], setExpanded);

    expect(actions[0].key).toBe("expand");
    actions[0].onClick?.(makeGroup());

    expect(setExpanded).toHaveBeenCalledWith(["g1"]);
  });

  it("should drop the group key when collapsing an expanded group", () => {
    const setExpanded = jest.fn();
    const actions = groupActionsFor(makeGroup(), ["g1", "other"], setExpanded);

    expect(actions[0].key).toBe("collapse");
    actions[0].onClick?.(makeGroup());

    expect(setExpanded).toHaveBeenCalledWith(["other"]);
  });

  it("should open the import modal from the add action", () => {
    const showModal = jest.fn();
    const actions = groupActionsFor(
      makeGroup(),
      [],
      jest.fn(),
      jest.fn(async () => {}),
      showModal,
    );

    actions.find((a) => a.key === "add")?.onClick?.(makeGroup());

    expect(showModal).toHaveBeenCalledTimes(1);
  });

  it("should delete the group, report success, and refresh the list", async () => {
    deleteSpecificationGroup.mockResolvedValue(undefined);
    const refreshGroups = jest.fn(async () => {});
    const actions = groupActionsFor(makeGroup(), [], jest.fn(), refreshGroups);

    actions.find((a) => a.key === "delete")?.onClick?.(makeGroup());
    await flushHandler();

    expect(deleteSpecificationGroup).toHaveBeenCalledWith("g1");
    expect(messageSuccess).toHaveBeenCalledWith("API group deleted");
    expect(refreshGroups).toHaveBeenCalled();
  });

  it("should report a failed group deletion instead of refreshing", async () => {
    deleteSpecificationGroup.mockRejectedValue(new Error("boom"));
    const refreshGroups = jest.fn(async () => {});
    const actions = groupActionsFor(makeGroup(), [], jest.fn(), refreshGroups);

    actions.find((a) => a.key === "delete")?.onClick?.(makeGroup());
    await flushHandler();

    expect(requestFailed).toHaveBeenCalledWith(
      "Delete failed",
      expect.any(Error),
    );
    expect(refreshGroups).not.toHaveBeenCalled();
  });
});

describe("getSpecActions handlers", () => {
  it("should expand a collapsed API row", () => {
    const setExpanded = jest.fn();
    const actions = specActionsFor(makeSpec(), [], setExpanded);

    actions[0].onClick?.(makeSpec());

    expect(setExpanded).toHaveBeenCalledWith(["m1"]);
  });

  it("should offer delete only for a deprecated API", () => {
    const deprecated = specActionsFor(makeSpec({ deprecated: true }));
    const active = specActionsFor(makeSpec({ deprecated: false }));

    expect(deprecated.some((a) => a.key === "delete")).toBe(true);
    expect(deprecated.some((a) => a.key === "deprecate")).toBe(false);
    expect(active.some((a) => a.key === "deprecate")).toBe(true);
    expect(active.some((a) => a.key === "delete")).toBe(false);
  });

  it("should delete a deprecated API, report success, and refresh", async () => {
    deleteSpecificationModel.mockResolvedValue(undefined);
    const refreshModels = jest.fn(async () => {});
    const actions = specActionsFor(
      makeSpec({ deprecated: true }),
      [],
      jest.fn(),
      refreshModels,
    );

    actions.find((a) => a.key === "delete")?.onClick?.(makeSpec());
    await flushHandler();

    expect(deleteSpecificationModel).toHaveBeenCalledWith("m1");
    expect(messageSuccess).toHaveBeenCalledWith("API deleted");
    expect(refreshModels).toHaveBeenCalled();
  });

  it("should report a failed API deletion", async () => {
    deleteSpecificationModel.mockRejectedValue(new Error("boom"));
    const refreshModels = jest.fn(async () => {});
    const actions = specActionsFor(
      makeSpec({ deprecated: true }),
      [],
      jest.fn(),
      refreshModels,
    );

    actions.find((a) => a.key === "delete")?.onClick?.(makeSpec());
    await flushHandler();

    expect(requestFailed).toHaveBeenCalledWith(
      "Delete failed",
      expect.any(Error),
    );
    expect(refreshModels).not.toHaveBeenCalled();
  });

  it("should deprecate an active API, report success, and refresh", async () => {
    deprecateModel.mockResolvedValue({});
    const refreshModels = jest.fn(async () => {});
    const actions = specActionsFor(
      makeSpec({ deprecated: false }),
      [],
      jest.fn(),
      refreshModels,
    );

    actions.find((a) => a.key === "deprecate")?.onClick?.(makeSpec());
    await flushHandler();

    expect(deprecateModel).toHaveBeenCalledWith("m1");
    expect(messageSuccess).toHaveBeenCalledWith("API deprecated");
    expect(refreshModels).toHaveBeenCalled();
  });

  it("should report a failed deprecation", async () => {
    deprecateModel.mockRejectedValue(new Error("boom"));
    const refreshModels = jest.fn(async () => {});
    const actions = specActionsFor(
      makeSpec({ deprecated: false }),
      [],
      jest.fn(),
      refreshModels,
    );

    actions.find((a) => a.key === "deprecate")?.onClick?.(makeSpec());
    await flushHandler();

    expect(requestFailed).toHaveBeenCalledWith(
      "Deprecate failed",
      expect.any(Error),
    );
    expect(refreshModels).not.toHaveBeenCalled();
  });

  it("should export a single API through the export action", async () => {
    exportSpecifications.mockResolvedValue(new File([], "spec.zip"));
    const actions = specActionsFor(makeSpec());

    actions.find((a) => a.key === "export")?.onClick?.(makeSpec());
    await flushHandler();

    // The export takes the API ids and, separately, the groups they belong to.
    expect(exportSpecifications).toHaveBeenCalledWith(["m1"], ["g1"]);
  });
});
