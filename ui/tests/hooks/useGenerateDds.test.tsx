/**
 * @jest-environment jsdom
 */
import React from "react";
import { renderHook, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { useGenerateDds } from "../../src/hooks/useGenerateDds";

const mockShowModal = jest.fn();
jest.mock("../../src/Modals", () => ({
  useModalsContext: () => ({
    showModal: mockShowModal,
    closeModal: jest.fn(),
  }),
}));

jest.mock("../../src/components/modal/GenerateDdsModal", () => ({
  GenerateDdsModal: ({
    onSubmit,
  }: {
    onSubmit?: (a: string, b: string) => void;
  }) => (
    <div data-testid="mock-generate-dds-modal">
      <button
        data-testid="mock-submit"
        onClick={() => onSubmit?.("template-123", "file-name")}
      >
        Mock Submit
      </button>
    </div>
  ),
}));

jest.mock("../../src/components/modal/DdsPreview", () => ({
  DdsPreview: () => <div data-testid="mock-dds-preview" />,
}));

describe("useGenerateDds", () => {
  it("shows form modal then preview modal on submit with correct args", () => {
    const { result } = renderHook(() => useGenerateDds());

    act(() => {
      result.current.showGenerateDdsModal("chain-1");
    });

    // Initially only the form modal should be shown.
    expect(mockShowModal).toHaveBeenCalledTimes(1);

    const formCall = mockShowModal.mock.calls[0][0] as {
      component: React.ReactElement;
    };
    expect(formCall.component.props.onSubmit).toBeDefined();

    // Trigger the form submit to show the preview modal.
    act(() => {
      formCall.component.props.onSubmit?.("template-123", "file-name");
    });

    // Now both modals should have been shown.
    expect(mockShowModal).toHaveBeenCalledTimes(2);

    const previewCall = mockShowModal.mock.calls[1][0] as {
      component: React.ReactElement;
    };
    expect(previewCall.component.type).toBeTruthy();
    expect(previewCall.component.props.chainId).toBe("chain-1");
    expect(previewCall.component.props.templateId).toBe("template-123");
    expect(previewCall.component.props.fileName).toBe("file-name");
  });
});
