/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import React from "react";
import { render, screen } from "@testing-library/react";
import {
  useRegisterChainHeaderActions,
  ChainHeaderActionsContextProvider,
} from "../../src/pages/ChainHeaderActionsContext.tsx";

function HeaderSlot({ children }: Readonly<{ children: React.ReactNode }>) {
  return <div data-testid="chain-header-slot">{children}</div>;
}

function TestRootWithRegistration({
  label,
  regDep,
}: Readonly<{
  label: string;
  regDep: number;
}>) {
  const [header, setHeader] = React.useState<React.ReactNode>(null);
  const registrationRef = React.useRef(0);
  const registerHeaderActions = React.useCallback(
    (actions: React.ReactNode) => {
      const generation = ++registrationRef.current;
      setHeader(actions);
      return () => {
        if (registrationRef.current === generation) {
          setHeader(null);
        }
      };
    },
    [],
  );
  return (
    <ChainHeaderActionsContextProvider value={{ registerHeaderActions }}>
      <Registrant label={label} regDep={regDep} />
      <HeaderSlot>{header}</HeaderSlot>
    </ChainHeaderActionsContextProvider>
  );
}

function Registrant({
  label,
  regDep,
}: Readonly<{ label: string; regDep: number }>) {
  useRegisterChainHeaderActions(
    <span data-testid={`hdr-${label}`}>{label}</span>,
    [regDep],
  );
  return null;
}

describe("useRegisterChainHeaderActions generation cleanup", () => {
  it("replaces header when a new registration supersedes the previous one", () => {
    const { rerender } = render(
      <TestRootWithRegistration label="first" regDep={1} />,
    );

    expect(screen.getByTestId("hdr-first")).toBeInTheDocument();

    rerender(<TestRootWithRegistration label="second" regDep={2} />);

    expect(screen.queryByTestId("hdr-first")).not.toBeInTheDocument();
    expect(screen.getByTestId("hdr-second")).toBeInTheDocument();
  });
});
