import { toErrorPayload } from "../../src/web/errorPayload";

describe("toErrorPayload", () => {
  test("carries the message at the top level and in responseBody", () => {
    const payload = toErrorPayload(new Error("Property file not found"));

    expect(payload.message).toBe("Property file not found");
    expect(payload.name).toBe("Error");
    expect(payload.responseBody.serviceName).toBe("CIP Extension");
    expect(payload.responseBody.errorMessage).toBe("Property file not found");
    expect(payload.responseBody.stackTrace).toContain(
      "Property file not found",
    );
  });

  test("is plain data, so it survives postMessage serialization", () => {
    const payload = toErrorPayload(new Error("boom"));

    expect(JSON.parse(JSON.stringify(payload))).toEqual(payload);
  });
});
