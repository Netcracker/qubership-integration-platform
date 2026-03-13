/**
 * @jest-environment jsdom
 */
import { render, screen } from "@testing-library/react";
import { UserPermissionsContext } from "../../src/permissions/UserPermissionsContext.tsx";
import { Require } from "../../src/permissions/Require.tsx";
import "@testing-library/jest-dom";

describe("Require", () => {
  it("should render children component when requirements are met", () => {
    render(
      <UserPermissionsContext.Provider value={{ chain: ["create", "update"] }}>
        <Require permissions={{ chain: ["update"] }} fallback={"fallback"}>
          Hello there!
        </Require>
      </UserPermissionsContext.Provider>,
    );
    expect(screen.queryByText("Hello there!")).toBeTruthy();
    expect(screen.queryByText("fallback")).toBeFalsy();
  });

  it("should render the fallback component when requirements aren't met", () => {
    render(
      <UserPermissionsContext.Provider value={{ chain: ["create", "update"] }}>
        <Require permissions={{ service: ["update"] }} fallback={"fallback"}>
          Hello there!
        </Require>
      </UserPermissionsContext.Provider>,
    );
    expect(screen.queryByText("Hello there!")).toBeFalsy();
    expect(screen.queryByText("fallback")).toBeTruthy();
  });
});
