import {
  configure,
  getConfig,
  onConfigChange,
  UserInfo,
} from "../src/appConfig";
import { DomainType } from "../src/api/apiTypes";

describe("appConfig - documentationBaseUrl", () => {
  test("is undefined by default", () => {
    expect(getConfig().documentationBaseUrl).toBeUndefined();
  });

  test("can be set via configure()", () => {
    configure({ documentationBaseUrl: "https://example.com/docs" });
    expect(getConfig().documentationBaseUrl).toBe("https://example.com/docs");
  });

  test("triggers config change listener", () => {
    const listener = jest.fn();
    const unsubscribe = onConfigChange(listener);

    configure({ documentationBaseUrl: "/new-docs" });

    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({ documentationBaseUrl: "/new-docs" }),
    );

    unsubscribe();
  });

  test("unsubscribe stops listener", () => {
    const listener = jest.fn();
    const unsubscribe = onConfigChange(listener);
    unsubscribe();

    configure({ documentationBaseUrl: "/another" });

    expect(listener).not.toHaveBeenCalled();
  });
});

describe("appConfig - userInfo", () => {
  afterEach(() => {
    configure({
      userInfo: {
        userName: undefined,
        email: undefined,
        tenantName: undefined,
        tenantId: undefined,
      },
    });
  });

  test("can be set via configure()", () => {
    configure({
      userInfo: {
        userName: "Tenant Admin",
        email: "admin@example.com",
        tenantName: "acme",
        tenantId: "tid-1",
      },
    });

    expect(getConfig().userInfo).toEqual({
      userName: "Tenant Admin",
      email: "admin@example.com",
      tenantName: "acme",
      tenantId: "tid-1",
    });
  });

  test("merges partial updates without losing earlier fields", () => {
    configure({
      userInfo: { userName: "Alice", tenantId: "tid-1" },
    });
    configure({ userInfo: { email: "alice@example.com" } });

    expect(getConfig().userInfo).toEqual({
      userName: "Alice",
      tenantId: "tid-1",
      email: "alice@example.com",
    });
  });

  test("notifies listeners on update", () => {
    const listener = jest.fn();
    const unsubscribe = onConfigChange(listener);

    configure({ userInfo: { userName: "Bob" } });

    expect(listener).toHaveBeenCalled();
    const [snapshot] = listener.mock.calls[0] as [{ userInfo?: UserInfo }];
    expect(snapshot.userInfo?.userName).toBe("Bob");

    unsubscribe();
  });
});

describe("appConfig - onLogout", () => {
  afterEach(() => {
    configure({ onLogout: undefined });
  });

  test("is undefined by default", () => {
    expect(getConfig().onLogout).toBeUndefined();
  });

  test("can be set via configure()", () => {
    const handler = jest.fn();
    configure({ onLogout: handler });

    expect(getConfig().onLogout).toBe(handler);
  });

  test("can be cleared by passing undefined", () => {
    configure({ onLogout: jest.fn() });
    configure({ onLogout: undefined });

    expect(getConfig().onLogout).toBeUndefined();
  });

  test("notifies listeners when handler changes", () => {
    const listener = jest.fn();
    const unsubscribe = onConfigChange(listener);

    configure({ onLogout: jest.fn() });

    expect(listener).toHaveBeenCalled();
    unsubscribe();
  });
});

describe("appConfig - domainTypes", () => {
  test("is undefined by default", () => {
    expect(getConfig().domainTypes).toBeUndefined();
  });

  test("can be set via configure() and notifies listeners", () => {
    const listener = jest.fn();
    const unsubscribe = onConfigChange(listener);

    configure({ domainTypes: [DomainType.CLASSIC] });

    expect(getConfig().domainTypes).toEqual([DomainType.CLASSIC]);
    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({ domainTypes: [DomainType.CLASSIC] }),
    );
    unsubscribe();
  });
});

describe("appConfig - productionMode", () => {
  test("is undefined by default", () => {
    expect(getConfig().productionMode).toBeUndefined();
  });

  test("can be set via configure() and notifies listeners", () => {
    const listener = jest.fn();
    const unsubscribe = onConfigChange(listener);

    configure({ productionMode: true });

    expect(getConfig().productionMode).toBe(true);
    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({ productionMode: true }),
    );
    unsubscribe();
  });
});

describe("appConfig - opensearchKafkaEnabled", () => {
  afterEach(() => {
    configure({ opensearchKafkaEnabled: undefined });
  });

  test("is undefined by default", () => {
    expect(getConfig().opensearchKafkaEnabled).toBeUndefined();
  });

  test("should notify listeners when the value changes", () => {
    const listener = jest.fn();
    const unsubscribe = onConfigChange(listener);

    configure({ opensearchKafkaEnabled: false });

    expect(getConfig().opensearchKafkaEnabled).toBe(false);
    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({ opensearchKafkaEnabled: false }),
    );
    unsubscribe();
  });

  test("should not notify listeners when the value is unchanged", () => {
    configure({ opensearchKafkaEnabled: true });
    const listener = jest.fn();
    const unsubscribe = onConfigChange(listener);

    configure({ opensearchKafkaEnabled: true });

    expect(listener).not.toHaveBeenCalled();
    unsubscribe();
  });

  test("should clear the value when undefined is passed", () => {
    configure({ opensearchKafkaEnabled: true });

    configure({ opensearchKafkaEnabled: undefined });

    expect(getConfig().opensearchKafkaEnabled).toBeUndefined();
  });

  test("should keep the value when the key is absent", () => {
    configure({ opensearchKafkaEnabled: true });

    configure({ documentationBaseUrl: "/doc" });

    expect(getConfig().opensearchKafkaEnabled).toBe(true);
  });
});
