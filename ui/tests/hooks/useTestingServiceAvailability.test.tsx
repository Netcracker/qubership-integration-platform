/**
 * @jest-environment jsdom
 */
import React from "react";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mockGetTestingServiceMode = jest.fn();
let mockIsVsCode = false;

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

// The client keeps the library's own retry policy on purpose. Turning retries
// off here would satisfy the no-retry-storm test through the harness rather than
// through the hook, which is what it is meant to guard.
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

  it("should report the service unavailable when the mode request fails", async () => {
    mockGetTestingServiceMode.mockRejectedValue(new Error("Network Error"));

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isAvailable).toBe(false);
  });

  // An absent testing service is a normal deployment, so it must not produce a
  // retry storm. The client above retries by the library default, so this holds
  // only because the hook itself refuses to.
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
});
