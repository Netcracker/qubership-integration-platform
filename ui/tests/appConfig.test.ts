import {
  configure,
  getConfig,
  onConfigChange,
  UserInfo,
} from "../src/appConfig";

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
