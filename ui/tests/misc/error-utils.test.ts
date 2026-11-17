import { getErrorDetails } from "../../src/misc/error-utils";

const EMPTY = { service: "", timestamp: "", message: "", stacktrace: "" };

describe("getErrorDetails", () => {
  test("takes the details from the error's responseBody", () => {
    const error = {
      responseBody: {
        serviceName: "CIP Extension",
        errorMessage: "Property file not found",
        errorDate: "2026-11-17T05:55:00.000Z",
        stackTrace: "Error: Property file not found\n    at readFile",
      },
    };

    expect(getErrorDetails(error)).toEqual({
      service: "CIP Extension",
      timestamp: "2026-11-17T05:55:00.000Z",
      message: "Property file not found",
      stacktrace: "Error: Property file not found\n    at readFile",
    });
  });

  test("falls back to the error message when there is no responseBody", () => {
    expect(getErrorDetails(new Error("Property file not found"))).toEqual({
      ...EMPTY,
      message: "Property file not found",
    });
  });

  test("falls back to the error message when responseBody has an empty errorMessage", () => {
    const error = Object.assign(new Error("Boom"), {
      responseBody: {
        serviceName: "QIP",
        errorMessage: "",
        errorDate: "",
        stackTrace: "",
      },
    });

    expect(getErrorDetails(error).message).toBe("Boom");
  });

  test("returns empty details for a value that is neither an error nor a body", () => {
    expect(getErrorDetails(undefined)).toEqual(EMPTY);
    expect(getErrorDetails(null)).toEqual(EMPTY);
  });
});
