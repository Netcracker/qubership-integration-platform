import { configure, getConfig, onConfigChange } from "../src/appConfig";

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
