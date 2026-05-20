import axios, { AxiosHeaders, type AxiosRequestConfig } from "axios";
import {
  installRequestHeaders,
  registerRestAxiosInstance,
} from "../../../src/api/rest/requestHeadersInterceptor";

function createCapturingInstance(baseURL: string) {
  let lastConfig: AxiosRequestConfig | undefined;

  const instance = axios.create({
    baseURL,
    adapter: (config) => {
      lastConfig = config;
      return Promise.resolve({
        data: null,
        status: 200,
        statusText: "OK",
        headers: {},
        config,
        request: {},
      });
    },
  });

  registerRestAxiosInstance(instance);

  return {
    instance,
    getLastHeaders: () =>
      AxiosHeaders.from(
        lastConfig?.headers as unknown as
          | AxiosHeaders
          | Record<string, string>
          | undefined,
      ),
  };
}

describe("requestHeadersInterceptor", () => {
  it("sets header from provider", async () => {
    const { instance, getLastHeaders } = createCapturingInstance(
      "https://api.example.com",
    );
    const handle = installRequestHeaders(() => ({ "X-Test": "1" }));

    await instance.get("/api/v1/test");

    expect(getLastHeaders().get("X-Test")).toBe("1");
    handle.eject();
  });

  it("overrides existing header from request config", async () => {
    const { instance, getLastHeaders } = createCapturingInstance(
      "https://api.example.com",
    );
    const handle = installRequestHeaders(() => ({ "X-Test": "2" }));

    await instance.get("/api/v1/test", { headers: { "X-Test": "1" } });

    expect(getLastHeaders().get("X-Test")).toBe("2");
    handle.eject();
  });

  it("deletes header when provider returns null", async () => {
    const { instance, getLastHeaders } = createCapturingInstance(
      "https://api.example.com",
    );
    const handle = installRequestHeaders(() => ({ Authorization: null }));

    await instance.get("/api/v1/test", {
      headers: { Authorization: "Bearer old" },
    });

    expect(getLastHeaders().get("Authorization")).toBeUndefined();
    handle.eject();
  });

  it("does not change header when provider returns undefined", async () => {
    const { instance, getLastHeaders } = createCapturingInstance(
      "https://api.example.com",
    );
    const handle = installRequestHeaders(() => ({ Authorization: undefined }));

    await instance.get("/api/v1/test", {
      headers: { Authorization: "Bearer old" },
    });

    expect(getLastHeaders().get("Authorization")).toBe("Bearer old");
    handle.eject();
  });

  it("does not attach headers for different origin absolute URL", async () => {
    const { instance, getLastHeaders } = createCapturingInstance(
      "https://api.example.com",
    );
    const handle = installRequestHeaders(() => ({ "X-Test": "1" }));

    await instance.get("https://evil.example.com/api/v1/test");

    expect(getLastHeaders().get("X-Test")).toBeUndefined();
    handle.eject();
  });

  it("attaches headers for same-origin relative URL when baseURL is not set", async () => {
    const { instance, getLastHeaders } = createCapturingInstance("");
    // Simulate browser location for base derivation when baseURL is missing.
    (globalThis as unknown as { window?: unknown }).window = {
      location: { href: "http://localhost:4200/chains" },
    };

    const handle = installRequestHeaders(() => ({ "X-Tenant-Id": "11111111" }));

    await instance.get("/api/v2/pip/catalog/folders/list");

    expect(getLastHeaders().get("X-Tenant-Id")).toBe("11111111");
    handle.eject();
  });
});
