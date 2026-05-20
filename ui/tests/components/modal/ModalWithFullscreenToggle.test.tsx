/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { ModalProps } from "antd";

Object.defineProperty(globalThis, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

jest.mock("antd", () => {
  const actual = jest.requireActual("antd") as Record<string, unknown>;
  return {
    ...actual,
    Modal: jest.fn(({ title }: any) => (
      <div data-testid="mock-modal">{title}</div>
    )),
  };
});

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

jest.mock("../../../src/components/modal/FullscreenButton.tsx", () => ({
  FullscreenButton: ({
    isFullscreen,
    onClick,
  }: {
    isFullscreen: boolean;
    onClick: () => void;
  }) => (
    <button
      data-testid="fullscreen-btn"
      data-is-fullscreen={String(isFullscreen)}
      onClick={onClick}
    />
  ),
}));

import { Modal } from "antd";
import { ModalWithFullscreenToggle } from "../../../src/components/modal/ModalWithFullscreenToggle";

const MockModal = Modal as jest.MockedFunction<typeof Modal>;
const getLastModalProps = (): ModalProps =>
  MockModal.mock.calls[MockModal.mock.calls.length - 1][0] as unknown as ModalProps;

describe("ModalWithFullscreenToggle", () => {
  it("should render the provided title when title prop is given", () => {
    render(<ModalWithFullscreenToggle title="My Dialog" />);

    expect(screen.getByText("My Dialog")).toBeInTheDocument();
  });

  it("should render a close button when component is mounted", () => {
    render(<ModalWithFullscreenToggle title="T" />);

    expect(screen.getByTitle("Close")).toBeInTheDocument();
  });

  it("should display the fullscreen icon when not in fullscreen mode", () => {
    render(<ModalWithFullscreenToggle title="T" />);

    expect(screen.getByTestId("fullscreen-btn")).toHaveAttribute(
      "data-is-fullscreen",
      "false",
    );
  });

  it("should call onCancel when the close button is clicked", () => {
    const onCancel = jest.fn();
    render(<ModalWithFullscreenToggle title="T" onCancel={onCancel} />);

    fireEvent.click(screen.getByTitle("Close"));

    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("should display the fullscreenExit icon when the fullscreen button is clicked", () => {
    render(<ModalWithFullscreenToggle title="T" />);

    fireEvent.click(screen.getByTestId("fullscreen-btn"));

    expect(screen.getByTestId("fullscreen-btn")).toHaveAttribute(
      "data-is-fullscreen",
      "true",
    );
  });

  it("should display the fullscreen icon when the fullscreen button is clicked twice", () => {
    render(<ModalWithFullscreenToggle title="T" />);
    const btn = screen.getByTestId("fullscreen-btn");

    fireEvent.click(btn);
    fireEvent.click(btn);

    expect(btn).toHaveAttribute("data-is-fullscreen", "false");
  });

  it("should use default width 90vw and height 90vh when no size props are provided", () => {
    render(<ModalWithFullscreenToggle title="T" />);
    const props = getLastModalProps();

    expect(props.width).toBe("90vw");
    expect(props.height).toBe("90vh");
  });

  it("should use custom width and height when size props are provided and not in fullscreen mode", () => {
    render(<ModalWithFullscreenToggle title="T" width={800} height={600} />);
    const props = getLastModalProps();

    expect(props.width).toBe(800);
    expect(props.height).toBe(600);
  });

  it("should use width 100vw and height 100vh when in fullscreen mode", () => {
    render(<ModalWithFullscreenToggle title="T" width={800} height={600} />);
    fireEvent.click(screen.getByTestId("fullscreen-btn"));
    const props = getLastModalProps();

    expect(props.width).toBe("100vw");
    expect(props.height).toBe("100vh");
  });

  it("should always pass open as true when rendered", () => {
    render(<ModalWithFullscreenToggle title="T" />);

    expect(getLastModalProps().open).toBe(true);
  });

  it("should always pass closable as false when rendered", () => {
    render(<ModalWithFullscreenToggle title="T" closable />);

    expect(getLastModalProps().closable).toBe(false);
  });

  it("should always pass maskClosable as false when rendered", () => {
    render(<ModalWithFullscreenToggle title="T" maskClosable />);

    expect(getLastModalProps().maskClosable).toBe(false);
  });

  it("should apply the base module class to className when not in fullscreen mode", () => {
    render(<ModalWithFullscreenToggle title="T" />);
    const { className } = getLastModalProps();

    expect(className).toContain("modal");
    expect(className).not.toContain("modal-fullscreen");
  });

  it("should add the fullscreen module class to className when fullscreen mode is entered", () => {
    render(<ModalWithFullscreenToggle title="T" />);
    fireEvent.click(screen.getByTestId("fullscreen-btn"));
    const { className } = getLastModalProps();

    expect(className).toContain("modal");
    expect(className).toContain("modal-fullscreen");
  });

  it("should preserve the caller-supplied className when a custom class is provided", () => {
    render(<ModalWithFullscreenToggle title="T" className="custom-modal" />);

    expect(getLastModalProps().className).toContain("custom-modal");
  });

  it("should apply module classes to all classNames slots when rendered", () => {
    render(<ModalWithFullscreenToggle title="T" />);
    const { classNames } = getLastModalProps();

    expect(classNames?.content).toContain("modal-content");
    expect(classNames?.body).toContain("modal-body");
    expect(classNames?.header).toContain("modal-header");
    expect(classNames?.footer).toContain("modal-footer");
    expect(classNames?.wrapper).toContain("modal-wrapper");
  });

  it("should add fullscreen module classes to classNames slots when fullscreen mode is entered", () => {
    render(<ModalWithFullscreenToggle title="T" />);
    fireEvent.click(screen.getByTestId("fullscreen-btn"));
    const { classNames } = getLastModalProps();

    expect(classNames?.content).toContain("modal-content-fullscreen");
    expect(classNames?.body).toContain("modal-body-fullscreen");
    expect(classNames?.wrapper).toContain("modal-wrapper-fullscreen");
  });

  it("should forward extra props to Modal when additional ModalProps are provided", () => {
    render(<ModalWithFullscreenToggle title="T" footer={null} />);

    expect(getLastModalProps().footer).toBeNull();
  });
});
