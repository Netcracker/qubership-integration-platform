/**
 * @jest-environment jsdom
 */
import { act, renderHook } from "@testing-library/react";
import {
  domainNamesRule,
  getDomainNameError,
  isDomainSelectionInvalid,
  K8S_RESOURCE_NAME_MAX_LENGTH,
  resolveDomains,
  useDomainTypes,
} from "../../src/components/SelectDomains";
import { DomainType, EngineDomain } from "../../src/api/apiTypes";
import { configure } from "../../src/appConfig";

const classicDomain: EngineDomain = {
  id: "default",
  name: "Default",
  replicas: 1,
  namespace: "qip",
  type: DomainType.CLASSIC,
};

describe("resolveDomains", () => {
  it("should return the listed domain when the catalog lists the ID", () => {
    expect(resolveDomains(["default"], [classicDomain], true)).toEqual([
      classicDomain,
    ]);
  });

  it("should keep an unlisted ID as a micro-domain when micro-domains are enabled", () => {
    expect(resolveDomains(["orders"], [classicDomain], true)).toEqual([
      {
        id: "orders",
        name: "orders",
        type: DomainType.MICRO,
        replicas: 0,
        namespace: "",
      },
    ]);
  });

  it("should drop an unlisted ID when micro-domains are disabled", () => {
    expect(
      resolveDomains(["default", "orders"], [classicDomain], false),
    ).toEqual([classicDomain]);
  });
});

// configure() can't unset domainTypes, so the default-config case runs first.
describe("useDomainTypes", () => {
  it("should report both domain types as loaded when the config sets none", () => {
    const { result } = renderHook(() => useDomainTypes());

    expect(result.current).toEqual({
      loaded: true,
      domainTypes: [DomainType.CLASSIC, DomainType.MICRO],
    });
  });

  it("should follow the config when its domain types change", () => {
    const { result } = renderHook(() => useDomainTypes());

    act(() => configure({ domainTypes: [DomainType.CLASSIC] }));

    expect(result.current).toEqual({
      loaded: true,
      domainTypes: [DomainType.CLASSIC],
    });
  });
});

describe("getDomainNameError", () => {
  it.each(["default", "a", "abc123", "a-b", "a--b", "ab-1-c"])(
    "should accept valid name %j",
    (name) => {
      expect(getDomainNameError(name)).toBeUndefined();
    },
  );

  it("should accept a name at the length limit", () => {
    expect(
      getDomainNameError("a".repeat(K8S_RESOURCE_NAME_MAX_LENGTH)),
    ).toBeUndefined();
  });

  it.each(["Foo", "ABC", "1abc", "-abc", "abc-", "a_b", "a b", "UPPER"])(
    "should reject invalid name %j",
    (name) => {
      expect(getDomainNameError(name)).toBeDefined();
    },
  );

  it("should reject a name exceeding the length limit", () => {
    expect(
      getDomainNameError("a".repeat(K8S_RESOURCE_NAME_MAX_LENGTH + 1)),
    ).toBeDefined();
  });

  it("should name the offending domain in the message", () => {
    expect(getDomainNameError("Foo")).toContain("Foo");
  });

  it("should report the backend length message with the domain name", () => {
    const name = "a".repeat(K8S_RESOURCE_NAME_MAX_LENGTH + 1);
    expect(getDomainNameError(name)).toBe(
      `Name exceeds maximum length of ${K8S_RESOURCE_NAME_MAX_LENGTH}: ${name}`,
    );
  });

  it("should report the backend pattern message with the domain name", () => {
    expect(getDomainNameError("Foo")).toBe(
      "Resource name should match pattern: [a-z](-*[a-z0-9])*: Foo",
    );
  });
});

describe("isDomainSelectionInvalid", () => {
  it("should report missing selection as invalid", () => {
    expect(isDomainSelectionInvalid(undefined)).toBe(true);
    expect(isDomainSelectionInvalid([])).toBe(true);
  });

  it("should report valid selection as valid", () => {
    expect(
      isDomainSelectionInvalid([{ name: "default", type: DomainType.CLASSIC }]),
    ).toBe(false);
  });

  it("should report selection with an invalid name as invalid", () => {
    expect(
      isDomainSelectionInvalid([{ name: "Foo", type: DomainType.CLASSIC }]),
    ).toBe(true);
  });
});

describe("domainNamesRule", () => {
  it("should resolve for valid domain names", async () => {
    if (!("validator" in domainNamesRule) || !domainNamesRule.validator) {
      throw new Error("domainNamesRule must define a validator");
    }
    await expect(
      domainNamesRule.validator(
        {},
        [{ name: "default", type: DomainType.CLASSIC }],
        () => {},
      ),
    ).resolves.toBeUndefined();
  });

  it("should reject invalid domain names with the offending name", async () => {
    if (!("validator" in domainNamesRule) || !domainNamesRule.validator) {
      throw new Error("domainNamesRule must define a validator");
    }
    await expect(
      domainNamesRule.validator(
        {},
        [{ name: "Foo", type: DomainType.CLASSIC }],
        () => {},
      ),
    ).rejects.toThrow("Foo");
  });
});
