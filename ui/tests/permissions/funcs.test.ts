import {
  getAllPermissions,
  getPermissions,
  hasPermissions,
} from "../../src/permissions/funcs.ts";
import { UserPermissions } from "../../src/permissions/types.ts";

describe("getPermissions", () => {
  it("should return all permissions when permissions are not specified in the configuration", () => {
    expect(getPermissions({})).toEqual(getAllPermissions());
  });

  it("should return permissions from the configuration when they are specified in it", () => {
    const permissions: UserPermissions = {
      chain: ["create", "read", "update", "delete"],
    };
    expect(getPermissions({ permissions })).toEqual(permissions);
  });
});

describe("hasPermissions", () => {
  it("should return false when required permissions not present in the provided permissions", () => {
    const permissions: UserPermissions = { chain: ["create"] };
    expect(hasPermissions(permissions, { chain: ["update"] })).toBeFalsy();
    expect(
      hasPermissions(permissions, { chain: ["create", "update"] }),
    ).toBeFalsy();
    expect(
      hasPermissions(permissions, { chain: ["create"], service: ["read"] }),
    ).toBeFalsy();
    expect(hasPermissions(permissions, { service: ["create"] })).toBeFalsy();
    expect(
      hasPermissions(permissions, {
        anyOf: [{ chain: ["update"] }, { service: ["create"] }],
      }),
    );
  });

  it("should return true when required permissions present in the provided permissions", () => {
    const permissions: UserPermissions = {
      chain: ["create", "update"],
      service: ["update"],
    };
    expect(hasPermissions(permissions, { chain: ["create"] })).toBeTruthy();
    expect(
      hasPermissions(permissions, {
        chain: ["create"],
        service: ["update"],
      }),
    ).toBeTruthy();
    expect(
      hasPermissions(permissions, {
        anyOf: [{ chain: ["create"] }, { service: ["update"] }],
      }),
    ).toBeTruthy();
  });
});
