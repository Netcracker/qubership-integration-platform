/**
 * @jest-environment jsdom
 */

import { render, screen } from "@testing-library/react";
import { UserPermissionsContext } from "../../src/permissions/UserPermissionsContext";
import { ProtectedButton } from "../../src/permissions/ProtectedButton";
import "@testing-library/jest-dom";

describe("ProtectedButton", () => {
  it("should render a button when requirements are met", () => {
    render(
      <UserPermissionsContext.Provider value={{ chain: ["update"] }}>
        <ProtectedButton
          require={{ chain: ["update"] }}
          tooltipProps={{}}
          buttonProps={{
            children: "Hello!",
          }}
        />
      </UserPermissionsContext.Provider>,
    );
    expect(screen.queryByRole("button")).toBeTruthy();
  });

  it("should not render a button when requirements aren't met and onDenied=hide", () => {
    render(
      <UserPermissionsContext.Provider value={{}}>
        <ProtectedButton
          onDenied="hide"
          require={{ chain: ["update"] }}
          tooltipProps={{}}
          buttonProps={{
            children: "Hello!",
          }}
        />
      </UserPermissionsContext.Provider>,
    );
    expect(screen.queryByRole("button")).toBeFalsy();
  });

  it("should render a disabled button when requirements aren't met and onDenied=disable", () => {
    render(
      <UserPermissionsContext.Provider value={{}}>
        <ProtectedButton
          onDenied="disable"
          require={{ chain: ["update"] }}
          tooltipProps={{}}
          buttonProps={{}}
        />
      </UserPermissionsContext.Provider>,
    );
    expect(screen.getByRole("button")).toBeDisabled();
  });
});
