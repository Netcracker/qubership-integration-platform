/**
 * @jest-environment jsdom
 */
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { EntityLabelComponent } from "../../../src/components/labels/EntityLabelComponent";

describe("EntityLabelComponent", () => {
  // antd sizes a Tag from fontSizeSM, which the app's base size of 13 resolves
  // to 10px, so a label read next to a 13px cell looked shrunken.
  it("should take the size of the text around it", () => {
    render(<EntityLabelComponent name="alpha" technical={false} />);

    expect(screen.getByText("alpha")).toHaveStyle({
      fontSize: "inherit",
      lineHeight: "22px",
    });
  });

  it("should mark a technical label", () => {
    const { container } = render(
      <EntityLabelComponent name="chain-id" technical />,
    );

    expect(container.querySelector(".ant-tag-blue")).toBeInTheDocument();
  });
});
