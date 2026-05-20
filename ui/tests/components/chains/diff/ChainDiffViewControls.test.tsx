/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Change } from "../../../../src/components/chains/diff/compare/types";

jest.mock("antd", () => ({
  Flex: ({ children }: any) => <div>{children}</div>,
  Tooltip: ({ children }: any) => <>{children}</>,
  Button: ({ icon, disabled, onClick }: any) => (
    <button disabled={disabled} onClick={onClick}>
      {icon}
    </button>
  ),
  Radio: {
    Group: ({ options, onChange }: any) => (
      <div data-testid="radio-group">
        {options?.map((opt: any) => (
          <button
            key={opt.value}
            data-testid={`radio-${opt.value}`}
            onClick={() => onChange({ target: { value: opt.value } })}
          >
            {opt.label}
          </button>
        ))}
      </div>
    ),
  },
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

import { ChainDiffViewControls } from "../../../../src/components/chains/diff/ChainDiffViewControls";

const c1: Change = { id: "c1", kind: "element" } as Change;
const c2: Change = { id: "c2", kind: "element" } as Change;
const c3: Change = { id: "c3", kind: "element" } as Change;
const changes = [c1, c2, c3];

const prevBtn = () =>
  screen.getByTestId("icon-previousChange").closest("button")!;
const nextBtn = () =>
  screen.getByTestId("icon-nextChange").closest("button")!;

describe("ChainDiffViewControls", () => {
  it("should disable the Previous button when the selected change is the first in the list", () => {
    render(
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId="c1"
        onSelectChange={jest.fn()}
        onViewTypeChange={jest.fn()}
      />,
    );

    expect(prevBtn()).toBeDisabled();
  });

  it("should enable the Previous button when the selected change is not the first in the list", () => {
    render(
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId="c2"
        onSelectChange={jest.fn()}
        onViewTypeChange={jest.fn()}
      />,
    );

    expect(prevBtn()).not.toBeDisabled();
  });

  it("should enable the Previous button when no change is selected", () => {
    render(
      <ChainDiffViewControls
        changes={changes}
        onSelectChange={jest.fn()}
        onViewTypeChange={jest.fn()}
      />,
    );

    expect(prevBtn()).not.toBeDisabled();
  });

  it("should disable the Next button when the selected change is the last in the list", () => {
    render(
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId="c3"
        onSelectChange={jest.fn()}
        onViewTypeChange={jest.fn()}
      />,
    );

    expect(nextBtn()).toBeDisabled();
  });

  it("should enable the Next button when the selected change is not the last in the list", () => {
    render(
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId="c2"
        onSelectChange={jest.fn()}
        onViewTypeChange={jest.fn()}
      />,
    );

    expect(nextBtn()).not.toBeDisabled();
  });

  it("should enable the Next button when no change is selected", () => {
    render(
      <ChainDiffViewControls
        changes={changes}
        onSelectChange={jest.fn()}
        onViewTypeChange={jest.fn()}
      />,
    );

    expect(nextBtn()).not.toBeDisabled();
  });

  it("should call onSelectChange with the previous change id when the Previous button is clicked", () => {
    const onSelectChange = jest.fn();
    render(
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId="c3"
        onSelectChange={onSelectChange}
        onViewTypeChange={jest.fn()}
      />,
    );

    fireEvent.click(prevBtn());

    expect(onSelectChange).toHaveBeenCalledWith("c2");
  });

  it("should not call onSelectChange when Previous is clicked while the first change is selected", () => {
    const onSelectChange = jest.fn();
    render(
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId="c1"
        onSelectChange={onSelectChange}
        onViewTypeChange={jest.fn()}
      />,
    );

    fireEvent.click(prevBtn());

    expect(onSelectChange).not.toHaveBeenCalled();
  });

  it("should call onSelectChange with the next change id when the Next button is clicked", () => {
    const onSelectChange = jest.fn();
    render(
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId="c1"
        onSelectChange={onSelectChange}
        onViewTypeChange={jest.fn()}
      />,
    );

    fireEvent.click(nextBtn());

    expect(onSelectChange).toHaveBeenCalledWith("c2");
  });

  it("should not call onSelectChange when Next is clicked while the last change is selected", () => {
    const onSelectChange = jest.fn();
    render(
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId="c3"
        onSelectChange={onSelectChange}
        onViewTypeChange={jest.fn()}
      />,
    );

    fireEvent.click(nextBtn());

    expect(onSelectChange).not.toHaveBeenCalled();
  });

  it("should call onSelectChange with the last change id when Next is clicked and no change is selected", () => {
    const onSelectChange = jest.fn();
    render(
      <ChainDiffViewControls
        changes={changes}
        onSelectChange={onSelectChange}
        onViewTypeChange={jest.fn()}
      />,
    );

    fireEvent.click(nextBtn());

    expect(onSelectChange).toHaveBeenCalledWith("c3");
  });

  it("should call onViewTypeChange with table when the Table option is selected", () => {
    const onViewTypeChange = jest.fn();
    render(
      <ChainDiffViewControls
        changes={[]}
        onSelectChange={jest.fn()}
        onViewTypeChange={onViewTypeChange}
      />,
    );

    fireEvent.click(screen.getByTestId("radio-table"));

    expect(onViewTypeChange).toHaveBeenCalledWith("table");
  });

  it("should call onViewTypeChange with graph when the Graph option is selected", () => {
    const onViewTypeChange = jest.fn();
    render(
      <ChainDiffViewControls
        changes={[]}
        onSelectChange={jest.fn()}
        onViewTypeChange={onViewTypeChange}
      />,
    );

    fireEvent.click(screen.getByTestId("radio-graph"));

    expect(onViewTypeChange).toHaveBeenCalledWith("graph");
  });
});
