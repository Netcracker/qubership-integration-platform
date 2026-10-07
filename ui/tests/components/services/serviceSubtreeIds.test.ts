/**
 * @jest-environment jsdom
 */

import { describe, expect, it } from "@jest/globals";
import { IntegrationSystemType } from "../../../src/api/apiTypes";
import type {
  IntegrationSystem,
  Specification,
  SpecificationGroup,
} from "../../../src/api/apiTypes";
import { buildServiceSubtreeIds } from "../../../src/components/services/serviceSubtreeIds";

function makeService(id: string): IntegrationSystem {
  return {
    id,
    name: id,
    description: id,
    type: IntegrationSystemType.EXTERNAL,
    activeEnvironmentId: "env-1",
    internalServiceName: id,
    protocol: "http",
    extendedProtocol: "http",
    specification: "",
  };
}

function makeGroup(
  id: string,
  systemId: string,
  specifications: Specification[] = [],
): SpecificationGroup {
  return {
    id,
    name: id,
    systemId,
    synchronization: false,
    specifications,
  };
}

function makeSpec(id: string, groupId: string): Specification {
  return {
    id,
    name: id,
    specificationGroupId: groupId,
    version: "1",
    source: "manual",
    systemId: "service-1",
  };
}

describe("buildServiceSubtreeIds", () => {
  it("should return only the service id when it has no loaded groups", () => {
    expect(buildServiceSubtreeIds(makeService("service-1"), {}, {})).toEqual([
      "service-1",
    ]);
  });

  it("should return service, group and spec ids for a loaded service", () => {
    const spec = makeSpec("spec-1", "group-1");
    const groups = [makeGroup("group-1", "service-1", [spec])];

    expect(
      buildServiceSubtreeIds(
        makeService("service-1"),
        { "service-1": groups },
        {},
      ),
    ).toEqual(["service-1", "group-1", "spec-1"]);
  });

  it("should prefer cached specs over the ones embedded in the group", () => {
    const embedded = makeSpec("embedded", "group-1");
    const cached = makeSpec("cached", "group-1");
    const groups = [makeGroup("group-1", "service-1", [embedded])];

    expect(
      buildServiceSubtreeIds(
        makeService("service-1"),
        { "service-1": groups },
        { "group-1": [cached] },
      ),
    ).toEqual(["service-1", "group-1", "cached"]);
  });

  it("should return group and spec ids for a specification group with cached specs", () => {
    const cached = makeSpec("cached", "group-1");
    const group = makeGroup("group-1", "service-1", [
      makeSpec("stale", "group-1"),
    ]);

    expect(buildServiceSubtreeIds(group, {}, { "group-1": [cached] })).toEqual([
      "group-1",
      "cached",
    ]);
  });

  it("should fall back to embedded specs for a group without cached specs", () => {
    const group = makeGroup("group-1", "service-1", [
      makeSpec("spec-1", "group-1"),
    ]);

    expect(buildServiceSubtreeIds(group, {}, {})).toEqual([
      "group-1",
      "spec-1",
    ]);
  });

  it("should return only the group id when it has no specs at all", () => {
    expect(
      buildServiceSubtreeIds(makeGroup("group-1", "service-1"), {}, {}),
    ).toEqual(["group-1"]);
  });

  it("should return only its own id for a specification leaf", () => {
    expect(
      buildServiceSubtreeIds(makeSpec("spec-1", "group-1"), {}, {}),
    ).toEqual(["spec-1"]);
  });
});
