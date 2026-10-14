/**
 * @jest-environment jsdom
 */
import React from "react";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mockGetTestingServiceMode = jest.fn();
let mockIsVsCode = false;
// Absent by default, which is the installation that named no mode.
let mockProductionMode: boolean | undefined;

jest.mock("../../src/appConfig", () => ({
  getConfig: () => ({ productionMode: mockProductionMode }),
}));

jest.mock("../../src/api/api", () => ({
  api: {
    getTestingServiceMode: () => mockGetTestingServiceMode(),
  },
}));

jest.mock("../../src/api/rest/vscodeExtensionApi", () => ({
  get isVsCode() {
    return mockIsVsCode;
  },
}));

import { useTestingServiceAvailability } from "../../src/hooks/useTestingServiceAvailability";

// The client keeps the library's own retry policy on purpose: it retries a
// failed query three times. Turning retries off here would satisfy the
// no-retry-storm test through the harness rather than through the hook, and the
// hook's own `retry: false` is what that test guards.
function createWrapper(queryClient = new QueryClient()) {
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

function renderAvailability(queryClient?: QueryClient) {
  return renderHook(() => useTestingServiceAvailability(), {
    wrapper: createWrapper(queryClient),
  });
}

describe("useTestingServiceAvailability", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockIsVsCode = false;
    mockProductionMode = undefined;
  });

  it("should report the service available when it answers with a non-production mode", async () => {
    mockGetTestingServiceMode.mockResolvedValue({ production: false });

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isAvailable).toBe(true));
    expect(result.current.isLoading).toBe(false);
  });

  it("should report the service unavailable when it runs in production mode", async () => {
    mockGetTestingServiceMode.mockResolvedValue({ production: true });

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isAvailable).toBe(false);
  });

  // The failure has to reach the query rather than be resolved inside the query
  // function: a swallowed one is a success the retry policy never sees.
  it("should report the service unavailable when the mode request fails", async () => {
    mockGetTestingServiceMode.mockRejectedValue(new Error("Network Error"));
    const queryClient = new QueryClient();

    const { result } = renderAvailability(queryClient);

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isAvailable).toBe(false);
    expect(queryClient.getQueryState(["testing-service", "mode"])?.status).toBe(
      "error",
    );
  });

  // An absent testing service is a normal deployment, so it must not produce a
  // retry storm. The client above retries by the library default, so this holds
  // only because the hook itself refuses to. Nothing may swallow the failure
  // inside the query function either: a rejection the hook resolves instead
  // would leave `retry: false` guarding nothing.
  it("should ask once when the mode request fails", async () => {
    mockGetTestingServiceMode.mockRejectedValue(new Error("Network Error"));

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(mockGetTestingServiceMode).toHaveBeenCalledTimes(1);

    // Long enough for the library's first backoff to have fired.
    await new Promise((resolve) => setTimeout(resolve, 1200));
    expect(mockGetTestingServiceMode).toHaveBeenCalledTimes(1);
  });

  it("should ask once when a second component mounts the hook", async () => {
    mockGetTestingServiceMode.mockResolvedValue({ production: false });
    const queryClient = new QueryClient();

    const first = renderAvailability(queryClient);
    await waitFor(() => expect(first.result.current.isAvailable).toBe(true));

    const second = renderAvailability(queryClient);
    await waitFor(() => expect(second.result.current.isAvailable).toBe(true));

    expect(mockGetTestingServiceMode).toHaveBeenCalledTimes(1);
  });

  it("should report the service unavailable and ask for nothing in the offline editor", async () => {
    mockIsVsCode = true;
    mockGetTestingServiceMode.mockResolvedValue({ production: false });

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isAvailable).toBe(false);
    expect(mockGetTestingServiceMode).not.toHaveBeenCalled();
  });

  // The installation's own answer settles it: a testing service deployed with
  // the wrong mode cannot open the section, because it is never asked.
  it("should report the service unavailable and ask for nothing on a production installation", async () => {
    mockProductionMode = true;
    mockGetTestingServiceMode.mockResolvedValue({ production: false });

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isAvailable).toBe(false);
    expect(mockGetTestingServiceMode).not.toHaveBeenCalled();
  });

  it("should ask the service when the installation names a mode that is not production", async () => {
    mockProductionMode = false;
    mockGetTestingServiceMode.mockResolvedValue({ production: false });

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isAvailable).toBe(true));
    expect(mockGetTestingServiceMode).toHaveBeenCalledTimes(1);
  });

  // Naming no mode leaves the question to the service, which answers production
  // unless it was told otherwise.
  it("should ask the service when the installation names no mode", async () => {
    mockGetTestingServiceMode.mockResolvedValue({ production: true });

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isAvailable).toBe(false);
    expect(mockGetTestingServiceMode).toHaveBeenCalledTimes(1);
  });
});
