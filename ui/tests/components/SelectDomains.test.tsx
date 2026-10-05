/**
 * @jest-environment jsdom
 */
import { act, renderHook } from "@testing-library/react";
import {
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
