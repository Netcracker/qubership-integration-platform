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

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

function renderAvailability() {
  return renderHook(() => useTestingServiceAvailability(), {
    wrapper: createWrapper(),
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

  it("should not retry a failing mode request", async () => {
    mockGetTestingServiceMode.mockRejectedValue(new Error("Network Error"));

    const { result } = renderAvailability();

    await waitFor(() => expect(result.current.isLoading).toBe(false));
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
