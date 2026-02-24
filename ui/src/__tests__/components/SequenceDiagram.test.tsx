/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest, beforeEach, afterEach } from "@jest/globals";
import { render, screen, cleanup, waitFor, fireEvent } from "@testing-library/react";
import React from "react";
import {
  DiagramLangType,
  DiagramMode,
  ElementsSequenceDiagrams,
} from "../../api/apiTypes.ts";

const mockCloseContainingModal = jest.fn();
const mockRequestFailed = jest.fn();
const mockDownloadFile = jest.fn();
const mermaidRenderCalls: unknown[][] = [];

jest.mock("../../ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

jest.mock("../../hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

let svgResult = "<svg>test-svg</svg>";

jest.mock("mermaid", () => ({
  __esModule: true,
  default: {
    render: (...args: unknown[]) => {
      mermaidRenderCalls.push(args);
      return Promise.resolve({ svg: svgResult });
    },
  },
}));

jest.mock("../../misc/download-utils.ts", () => ({
  downloadFile: (...args: unknown[]) => mockDownloadFile(...args),
}));

jest.mock("../../components/modal/SequenceDiagram.module.css", () => ({
  __esModule: true,
  default: { loader: "loader-class" },
}));

jest.mock("../../icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

import { SequenceDiagram } from "../../components/modal/SequenceDiagram.tsx";

function makeDiagrams(): ElementsSequenceDiagrams {
  return {
    [DiagramMode.FULL]: {
      chainId: "chain-1",
      diagramSources: {
        [DiagramLangType.MERMAID]: "sequenceDiagram; A->>B: test",
        [DiagramLangType.PLANT_UML]: "@startuml\nA->B\n@enduml",
      },
    },
    [DiagramMode.SIMPLE]: {
      chainId: "chain-1",
      diagramSources: {
        [DiagramLangType.MERMAID]: "sequenceDiagram; A->>B: simple",
        [DiagramLangType.PLANT_UML]: "@startuml\nA->B simple\n@enduml",
      },
    },
  };
}

describe("SequenceDiagram", () => {
  beforeEach(() => {
    mermaidRenderCalls.length = 0;
    mockCloseContainingModal.mockClear();
    mockRequestFailed.mockClear();
    mockDownloadFile.mockClear();
    svgResult = "<svg>test-svg</svg>";
  });

  afterEach(() => {
    cleanup();
  });

  it("should render modal with default title and tabs", async () => {
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockResolvedValue(makeDiagrams());

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    // Modal title
    expect(screen.getByText("Sequence Diagram")).toBeDefined();
    // Tabs
    expect(screen.getByText("Full")).toBeDefined();
    expect(screen.getByText("Simple")).toBeDefined();
    // Export button
    expect(screen.getByText("Export")).toBeDefined();
    // Down icon
    expect(screen.getByTestId("icon").getAttribute("data-icon")).toBe("down");
    // Provider called
    expect(provider).toHaveBeenCalled();

    // Wait for mermaid.render to be called (async effect chain)
    await waitFor(() => {
      expect(mermaidRenderCalls.length).toBeGreaterThan(0);
    });

    expect(mermaidRenderCalls[0][0]).toContain("seq-diagram-");
    expect(mermaidRenderCalls[0][1]).toBe("sequenceDiagram; A->>B: test");
  });

  it("should render modal with custom title", () => {
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockResolvedValue(makeDiagrams());

    render(
      <SequenceDiagram
        title="My Diagram"
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    expect(screen.getByText("My Diagram")).toBeDefined();
  });

  it("should show notification on provider error", async () => {
    const error = new Error("Network error");
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockRejectedValue(error);

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to get sequence diagram",
        error,
      );
    });
  });

  it("should not call mermaid.render when provider fails", async () => {
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockRejectedValue(new Error("fail"));

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalled();
    });

    expect(mermaidRenderCalls.length).toBe(0);
  });

  it("should inject rendered SVG into the DOM", async () => {
    svgResult = '<svg id="injected-svg">diagram</svg>';
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockResolvedValue(makeDiagrams());

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    await waitFor(() => {
      expect(document.querySelector("#injected-svg")).not.toBeNull();
    });
  });

  it("should switch to Simple tab and render simple diagram source", async () => {
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockResolvedValue(makeDiagrams());

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    // Wait for initial render
    await waitFor(() => {
      expect(mermaidRenderCalls.length).toBeGreaterThan(0);
    });

    const callsBefore = mermaidRenderCalls.length;

    // Click Simple tab
    screen.getByText("Simple").click();

    await waitFor(() => {
      const newCalls = mermaidRenderCalls.slice(callsBefore);
      expect(newCalls.length).toBeGreaterThan(0);
      expect(newCalls[0][1]).toBe("sequenceDiagram; A->>B: simple");
    });
  });

  it("should not call mermaid.render for empty diagram source", async () => {
    const emptyDiagrams: ElementsSequenceDiagrams = {
      [DiagramMode.FULL]: {
        chainId: "chain-1",
        diagramSources: {
          [DiagramLangType.MERMAID]: "",
          [DiagramLangType.PLANT_UML]: "",
        },
      },
      [DiagramMode.SIMPLE]: {
        chainId: "chain-1",
        diagramSources: {
          [DiagramLangType.MERMAID]: "",
          [DiagramLangType.PLANT_UML]: "",
        },
      },
    };
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockResolvedValue(emptyDiagrams);

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    // Wait for provider to resolve
    await waitFor(() => {
      expect(provider).toHaveBeenCalled();
    });

    // Give time for any mermaid.render that might (incorrectly) be called
    await new Promise((r) => setTimeout(r, 100));

    expect(mermaidRenderCalls.length).toBe(0);
  });

  it("should export Mermaid file via dropdown menu click", async () => {
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockResolvedValue(makeDiagrams());

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    // Wait for diagrams to load
    await waitFor(() => {
      expect(mermaidRenderCalls.length).toBeGreaterThan(0);
    });

    // Open dropdown
    const exportButton = screen.getByText("Export");
    fireEvent.mouseEnter(exportButton);
    fireEvent.click(exportButton);

    // Wait for menu items to appear in the portal
    await waitFor(() => {
      expect(screen.getByText("Mermaid")).toBeDefined();
    });

    // Click Mermaid export option
    const mermaidItem = screen.getByText("Mermaid");
    fireEvent.click(mermaidItem);

    await waitFor(() => {
      expect(mockDownloadFile).toHaveBeenCalled();
    });

    const file = mockDownloadFile.mock.calls[0][0] as File;
    expect(file.name).toContain(".mmd");
    expect(file.name).toContain("test-sequence-full-diagram__e1");
    expect(file.type).toBe("text/plain");
  });

  it("should export SVG file via dropdown menu click", async () => {
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockResolvedValue(makeDiagrams());

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    await waitFor(() => {
      expect(mermaidRenderCalls.length).toBeGreaterThan(0);
    });

    const exportButton = screen.getByText("Export");
    fireEvent.mouseEnter(exportButton);
    fireEvent.click(exportButton);

    await waitFor(() => {
      expect(screen.getByText("SVG")).toBeDefined();
    });

    fireEvent.click(screen.getByText("SVG"));

    await waitFor(() => {
      expect(mockDownloadFile).toHaveBeenCalled();
    });

    const file = mockDownloadFile.mock.calls[0][0] as File;
    expect(file.name).toContain(".svg");
    expect(file.type).toBe("image/svg+xml");
  });

  it("should export PlantUML file via dropdown menu click", async () => {
    const provider = jest
      .fn<() => Promise<ElementsSequenceDiagrams>>()
      .mockResolvedValue(makeDiagrams());

    render(
      <SequenceDiagram
        diagramProvider={provider}
        entityId="e1"
        fileNamePrefix="test"
      />,
    );

    await waitFor(() => {
      expect(mermaidRenderCalls.length).toBeGreaterThan(0);
    });

    const exportButton = screen.getByText("Export");
    fireEvent.mouseEnter(exportButton);
    fireEvent.click(exportButton);

    await waitFor(() => {
      expect(screen.getByText("PlantUML")).toBeDefined();
    });

    fireEvent.click(screen.getByText("PlantUML"));

    await waitFor(() => {
      expect(mockDownloadFile).toHaveBeenCalled();
    });

    const file = mockDownloadFile.mock.calls[0][0] as File;
    expect(file.name).toContain(".puml");
    expect(file.type).toBe("text/plain");
  });
});
