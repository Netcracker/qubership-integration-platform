/**
 * @jest-environment jsdom
 */
import type { AxiosResponse } from "axios";
import { getFileFromResponse } from "../../src/misc/download-utils";

function response(headers: Record<string, string>): AxiosResponse<Blob> {
  return {
    data: new Blob(["payload"], { type: "text/csv" }),
    headers,
  } as unknown as AxiosResponse<Blob>;
}

describe("getFileFromResponse", () => {
  it("should take the name from content-disposition when the response carries one", () => {
    const file = getFileFromResponse(
      response({ "content-disposition": 'attachment; filename="runs.csv"' }),
      "fallback.csv",
    );

    expect(file.name).toBe("runs.csv");
    expect(file.type).toBe("text/csv");
  });

  // Without the fallback the File constructor stringifies undefined, and the name
  // "undefined" is truthy, so downloadFile's own default never fires.
  it("should fall back to the given name when no header names the file", () => {
    expect(getFileFromResponse(response({}), "fallback.csv").name).toBe(
      "fallback.csv",
    );
  });

  it("should never name a file undefined", () => {
    expect(getFileFromResponse(response({})).name).not.toBe("undefined");
  });
});
