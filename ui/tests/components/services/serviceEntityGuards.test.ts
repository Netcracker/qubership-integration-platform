/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

import { describe, it, expect } from "@jest/globals";
import {
  isApi,
  isApiGroup,
  isIntegrationSystem,
} from "../../../src/components/services/ServicesTreeTable";
import { getUsageStatus } from "../../../src/components/services/utils";
import { IntegrationSystemType } from "../../../src/api/apiTypes";
import type {
  Api,
  ApiGroup,
  ContextSystem,
  IntegrationSystem,
} from "../../../src/api/apiTypes";
import type { ServiceEntity } from "../../../src/components/services/ServicesTreeTable";

/**
 * The services tree mixes three entity levels in one data source and tells them apart by shape
 * alone, so each guard has to stay narrow enough not to claim a neighbouring level.
 */
describe("service entity guards", () => {
  const service = {
    id: "s1",
    name: "Service",
    type: IntegrationSystemType.EXTERNAL,
  } as unknown as IntegrationSystem;

  const contextService = {
    id: "c1",
    name: "Context",
    type: IntegrationSystemType.CONTEXT,
  } as unknown as ContextSystem;

  const group = {
    id: "g1",
    name: "Group",
    systemId: "s1",
    synchronization: false,
  } as unknown as ApiGroup;

  const api = {
    id: "m1",
    name: "API",
    specificationGroupId: "g1",
    version: "1.0.0",
    source: "MANUAL",
  } as unknown as Api;

  it("should recognise a service and reject a context service", () => {
    expect(isIntegrationSystem(service as ServiceEntity)).toBe(true);
    expect(isIntegrationSystem(contextService as ServiceEntity)).toBe(false);
  });

  it("should not mistake a group or an API for a service", () => {
    expect(isIntegrationSystem(group as ServiceEntity)).toBe(false);
    expect(isIntegrationSystem(api as ServiceEntity)).toBe(false);
  });

  it("should recognise an API group by its service link and sync flag", () => {
    expect(isApiGroup(group as ServiceEntity)).toBe(true);
    expect(isApiGroup(service as ServiceEntity)).toBe(false);
    expect(isApiGroup(api as ServiceEntity)).toBe(false);
  });

  it("should recognise an API by group link, version, and source", () => {
    expect(isApi(api as ServiceEntity)).toBe(true);
    expect(isApi(group as ServiceEntity)).toBe(false);
    expect(isApi(service as ServiceEntity)).toBe(false);
  });

  it("should not claim an API when only part of its shape is present", () => {
    const partial = {
      id: "m2",
      specificationGroupId: "g1",
      version: "1.0.0",
    } as unknown as ServiceEntity;

    expect(isApi(partial)).toBe(false);
  });
});

describe("getUsageStatus", () => {
  const base = {
    id: "m1",
    name: "API",
    specificationGroupId: "g1",
    version: "1.0.0",
    source: "MANUAL",
  };

  it("should report a deprecated API regardless of its chains", () => {
    const element = {
      ...base,
      deprecated: true,
      chains: [{ id: "c1" }],
    } as unknown as Api;

    expect(getUsageStatus(element)).toBe("Deprecated");
  });

  it("should report an API used by at least one chain as in use", () => {
    const element = {
      ...base,
      deprecated: false,
      chains: [{ id: "c1" }],
    } as unknown as Api;

    expect(getUsageStatus(element)).toBe("In use");
  });

  it("should report an unused API as new", () => {
    expect(
      getUsageStatus({ ...base, deprecated: false } as unknown as Api),
    ).toBe("New");
    expect(
      getUsageStatus({
        ...base,
        deprecated: false,
        chains: [],
      } as unknown as Api),
    ).toBe("New");
  });
});
