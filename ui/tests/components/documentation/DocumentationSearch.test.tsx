/**
 * @jest-environment jsdom
 */
import { render, screen, fireEvent, act } from "@testing-library/react";
import "@testing-library/jest-dom";

const mockNavigate = jest.fn();

jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
}));

const mockSearch = jest.fn();
const mockGetSearchDetailSegments = jest.fn();
const mockLoadNames = jest.fn();
const mockLoadPaths = jest.fn();

jest.mock("../../../src/hooks/useDocumentation", () => ({
  useDocumentation: () => ({
    search: mockSearch,
    getSearchDetailSegments: mockGetSearchDetailSegments,
    loadNames: mockLoadNames,
    loadPaths: mockLoadPaths,
  }),
}));

jest.mock("../../../src/services/documentation/documentationUrlUtils", () => ({
  DOCUMENTATION_ROUTE_BASE: "/doc",
  toDocRoutePath: (base: string, path: string) => `${base}/${path}`,
}));

jest.mock(
  "../../../src/components/documentation/DocumentationSearch.module.css",
  () => ({
    __esModule: true,
    default: {
      container: "container",
      loading: "loading",
      results: "results",
      resultItem: "resultItem",
      resultTitleText: "resultTitleText",
      breadcrumb: "breadcrumb",
      snippet: "snippet",
      didYouMean: "didYouMean",
      didYouMeanTerm: "didYouMeanTerm",
      emptyState: "emptyState",
    },
  }),
);

import { DocumentationSearch } from "../../../src/components/documentation/DocumentationSearch";

const flushPromises = async () => {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
};

const typeAndSearch = async (value: string) => {
  const input = screen.getByPlaceholderText("Search documentation...");
  fireEvent.change(input, { target: { value } });
  await act(async () => {
    jest.advanceTimersByTime(300);
  });
  await flushPromises();
};

beforeEach(() => {
  jest.clearAllMocks();
  jest.useFakeTimers();
  mockSearch.mockResolvedValue([]);
  mockLoadNames.mockResolvedValue({});
  mockGetSearchDetailSegments.mockResolvedValue([]);
  mockLoadPaths.mockResolvedValue([]);
});

afterEach(() => {
  jest.useRealTimers();
});

describe("DocumentationSearch", () => {
  it("renders search input", () => {
    render(<DocumentationSearch />);
    expect(
      screen.getByPlaceholderText("Search documentation..."),
    ).toBeInTheDocument();
  });

  it("does not search when query is empty", async () => {
    render(<DocumentationSearch />);
    const input = screen.getByPlaceholderText("Search documentation...");
    fireEvent.change(input, { target: { value: "" } });

    await act(async () => {
      jest.advanceTimersByTime(300);
    });

    expect(mockSearch).not.toHaveBeenCalled();
  });

  it("does not search when query is only whitespace", async () => {
    render(<DocumentationSearch />);
    await typeAndSearch("   ");

    expect(mockSearch).not.toHaveBeenCalled();
  });

  it("debounces search calls", async () => {
    mockSearch.mockResolvedValue([{ ref: 0, score: 1, terms: ["test"] }]);
    mockLoadNames.mockResolvedValue({ 0: ["Overview"] });

    render(<DocumentationSearch />);
    const input = screen.getByPlaceholderText("Search documentation...");

    fireEvent.change(input, { target: { value: "te" } });
    fireEvent.change(input, { target: { value: "tes" } });
    fireEvent.change(input, { target: { value: "test" } });

    expect(mockSearch).not.toHaveBeenCalled();

    await act(async () => {
      jest.advanceTimersByTime(300);
    });

    expect(mockSearch).toHaveBeenCalledTimes(1);
    expect(mockSearch).toHaveBeenCalledWith("test");
  });

  it("displays search results with titles", async () => {
    mockSearch.mockResolvedValue([
      { ref: 0, score: 5, terms: ["test"] },
      { ref: 1, score: 3, terms: ["test"] },
    ]);
    mockLoadNames.mockResolvedValue({
      0: ["Chains", "Service Call"],
      1: ["Overview"],
    });
    mockGetSearchDetailSegments.mockResolvedValue([
      [{ text: "matched ", isHit: false }, { text: "test", isHit: true }],
    ]);

    render(<DocumentationSearch />);
    await typeAndSearch("test");

    expect(screen.getByText("Service Call")).toBeInTheDocument();
    expect(screen.getByText("Overview")).toBeInTheDocument();
  });

  it("shows 'No results found' when search returns empty", async () => {
    render(<DocumentationSearch />);
    await typeAndSearch("nonexistent");

    expect(screen.getByText("No results found")).toBeInTheDocument();
  });

  it("navigates on result click", async () => {
    mockSearch.mockResolvedValue([{ ref: 0, score: 5, terms: ["service"] }]);
    mockLoadNames.mockResolvedValue({ 0: ["Service Call"] });
    mockLoadPaths.mockResolvedValue(["01__Chains/service_call.md"]);
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("service");

    // Click on the result title — the click handler is on the List.Item
    fireEvent.click(screen.getByText("Service Call"));

    await flushPromises();

    expect(mockLoadPaths).toHaveBeenCalled();
    expect(mockNavigate).toHaveBeenCalledWith(
      "/doc/01__Chains/service_call",
    );
  });

  it("calls onSelect prop instead of navigate when provided", async () => {
    const onSelect = jest.fn();
    mockSearch.mockResolvedValue([{ ref: 0, score: 5, terms: ["overview"] }]);
    mockLoadNames.mockResolvedValue({ 0: ["Overview"] });
    mockLoadPaths.mockResolvedValue(["00__Overview/overview.md"]);
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch onSelect={onSelect} />);
    await typeAndSearch("overview");

    fireEvent.click(screen.getByText("Overview"));

    await flushPromises();

    expect(onSelect).toHaveBeenCalledWith("/doc/00__Overview/overview");
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("clears results when query is cleared", async () => {
    mockSearch.mockResolvedValue([{ ref: 0, score: 5, terms: ["test"] }]);
    mockLoadNames.mockResolvedValue({ 0: ["Overview"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("test");

    expect(screen.getByText("Overview")).toBeInTheDocument();

    // Clear input
    fireEvent.change(screen.getByPlaceholderText("Search documentation..."), {
      target: { value: "" },
    });

    expect(screen.queryByText("Overview")).not.toBeInTheDocument();
    expect(screen.queryByText("No results found")).not.toBeInTheDocument();
  });

  it("handles search errors gracefully", async () => {
    const spy = jest.spyOn(console, "error").mockImplementation(() => {});
    mockSearch.mockRejectedValue(new Error("Network error"));

    render(<DocumentationSearch />);
    await typeAndSearch("error");

    expect(screen.getByText("No results found")).toBeInTheDocument();
    expect(spy).toHaveBeenCalledWith("Search failed:", expect.any(Error));
    spy.mockRestore();
  });

  it("discards stale results when a new search starts", async () => {
    // First search resolves slowly
    let resolveFirst!: (v: unknown) => void;
    const firstPromise = new Promise((r) => { resolveFirst = r; });
    mockSearch.mockReturnValueOnce(firstPromise);
    mockLoadNames.mockResolvedValue({ 0: ["Stale"] });

    render(<DocumentationSearch />);
    const input = screen.getByPlaceholderText("Search documentation...");

    // Start first search
    fireEvent.change(input, { target: { value: "first" } });
    await act(async () => {
      jest.advanceTimersByTime(300);
    });

    // Start second search before first resolves
    mockSearch.mockResolvedValueOnce([{ ref: 1, score: 5, terms: ["second"] }]);
    mockLoadNames.mockResolvedValue({ 1: ["Fresh"] });
    fireEvent.change(input, { target: { value: "second" } });
    await act(async () => {
      jest.advanceTimersByTime(300);
    });
    await flushPromises();

    // Now resolve the first search — its results should be discarded
    resolveFirst([{ ref: 0, score: 3, terms: ["first"] }]);
    await flushPromises();

    // Only the second (latest) result should be shown
    expect(screen.queryByText("Stale")).not.toBeInTheDocument();
    expect(screen.getByText("Fresh")).toBeInTheDocument();
  });

  it("handles missing path on result click", async () => {
    mockSearch.mockResolvedValue([{ ref: 99, score: 5, terms: ["missing"] }]);
    mockLoadNames.mockResolvedValue({ 99: ["Missing Doc"] });
    mockLoadPaths.mockResolvedValue([]); // ref 99 has no path
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("missing");

    fireEvent.click(screen.getByText("Missing Doc"));
    await flushPromises();

    // Should not navigate when path is missing
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("handles loadPaths error on result click", async () => {
    const spy = jest.spyOn(console, "error").mockImplementation(() => {});
    mockSearch.mockResolvedValue([{ ref: 0, score: 5, terms: ["overview"] }]);
    mockLoadNames.mockResolvedValue({ 0: ["Overview"] });
    mockLoadPaths.mockRejectedValue(new Error("Load failed"));
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("overview");

    fireEvent.click(screen.getByText("Overview"));
    await flushPromises();

    // Should not crash, just log error
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(spy).toHaveBeenCalledWith(
      "Failed to get document path:",
      expect.any(Error),
    );
    spy.mockRestore();
  });

  it("displays breadcrumb path for results with multi-part names", async () => {
    mockSearch.mockResolvedValue([{ ref: 0, score: 5, terms: ["call"] }]);
    mockLoadNames.mockResolvedValue({
      0: ["Chains", "Routing", "Service Call"],
    });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("call");

    expect(screen.getByText("Service Call")).toBeInTheDocument();
    expect(screen.getByText("Chains → Routing")).toBeInTheDocument();
  });

  it("does not display breadcrumb for single-part names", async () => {
    mockSearch.mockResolvedValue([{ ref: 0, score: 5, terms: ["overview"] }]);
    mockLoadNames.mockResolvedValue({ 0: ["Overview"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("overview");

    expect(screen.getByText("Overview")).toBeInTheDocument();
    expect(screen.queryByText("→")).not.toBeInTheDocument();
  });

  it("shows 'did you mean' when query words differ from matched terms", async () => {
    mockSearch.mockResolvedValue([
      { ref: 0, score: 3, terms: ["transformation"] },
    ]);
    mockLoadNames.mockResolvedValue({ 0: ["Transformation"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("transformabion");

    expect(screen.getByText(/Did you mean:/)).toBeInTheDocument();
    expect(screen.getByText("transformation")).toBeInTheDocument();
  });

  it("does not show 'did you mean' when query matches terms exactly", async () => {
    mockSearch.mockResolvedValue([
      { ref: 0, score: 5, terms: ["http"] },
    ]);
    mockLoadNames.mockResolvedValue({ 0: ["HTTP Trigger"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("HTTP");

    expect(screen.getByText("HTTP Trigger")).toBeInTheDocument();
    expect(screen.queryByText(/Did you mean:/)).not.toBeInTheDocument();
  });

  it("clicking 'did you mean' term sets it as query", async () => {
    mockSearch.mockResolvedValue([
      { ref: 0, score: 3, terms: ["transformation"] },
    ]);
    mockLoadNames.mockResolvedValue({ 0: ["Transformation"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("transformabion");

    const suggestion = screen.getByText("transformation");
    fireEvent.click(suggestion);

    const input = screen.getByPlaceholderText(
      "Search documentation...",
    ) as HTMLInputElement;
    expect(input.value).toBe("transformation");
  });

  it("does not show 'did you mean' for prefix searches", async () => {
    // "tr" is a prefix of "trigger" — normal prefix search, not a typo
    mockSearch.mockResolvedValue([
      { ref: 0, score: 5, terms: ["trigger"] },
      { ref: 1, score: 3, terms: ["transformation"] },
    ]);
    mockLoadNames.mockResolvedValue({
      0: ["HTTP Trigger"],
      1: ["Transformation"],
    });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("tr");

    expect(screen.getByText("HTTP Trigger")).toBeInTheDocument();
    expect(screen.queryByText(/Did you mean:/)).not.toBeInTheDocument();
  });

  it("displays breadcrumb for two-part names", async () => {
    mockSearch.mockResolvedValue([{ ref: 0, score: 5, terms: ["overview"] }]);
    mockLoadNames.mockResolvedValue({ 0: ["Getting Started", "Overview"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("overview");

    expect(screen.getByText("Overview")).toBeInTheDocument();
    expect(screen.getByText("Getting Started")).toBeInTheDocument();
  });

  it("shows only typo corrections in 'did you mean', not prefix-matched words", async () => {
    // Query: "http transformabion" — "http" is exact match, "transformabion" is typo
    // Should suggest only "transformation", not "http transformation"
    mockSearch.mockResolvedValue([
      { ref: 0, score: 3, terms: ["http", "transformation"] },
    ]);
    mockLoadNames.mockResolvedValue({ 0: ["HTTP Transformation"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("http transformabion");

    expect(screen.getByText(/Did you mean:/)).toBeInTheDocument();
    expect(screen.getByText("transformation")).toBeInTheDocument();
  });

  it("does not show 'did you mean' when all query words match terms", async () => {
    // Multi-word query where both words match
    mockSearch.mockResolvedValue([
      { ref: 0, score: 5, terms: ["http", "trigger"] },
    ]);
    mockLoadNames.mockResolvedValue({ 0: ["HTTP Trigger"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("http trigger");

    expect(screen.getByText("HTTP Trigger")).toBeInTheDocument();
    expect(screen.queryByText(/Did you mean:/)).not.toBeInTheDocument();
  });

  it("clears 'did you mean' when query is cleared", async () => {
    mockSearch.mockResolvedValue([
      { ref: 0, score: 3, terms: ["transformation"] },
    ]);
    mockLoadNames.mockResolvedValue({ 0: ["Transformation"] });
    mockGetSearchDetailSegments.mockResolvedValue([]);

    render(<DocumentationSearch />);
    await typeAndSearch("transformabion");

    expect(screen.getByText(/Did you mean:/)).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText("Search documentation..."), {
      target: { value: "" },
    });

    expect(screen.queryByText(/Did you mean:/)).not.toBeInTheDocument();
  });
});
