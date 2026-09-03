import React, { useCallback, useMemo, useRef, useState } from "react";
import { render, type RenderResult } from "@testing-library/react";
import { Modals } from "../../src/Modals.tsx";
import { ChainHeaderActionsContextProvider } from "../../src/pages/ChainHeaderActionsContext.tsx";
import { TestQueryClientProvider } from "./queryClientHarness.tsx";

/**
 * Wraps chain tab pages so `useRegisterChainHeaderActions` runs and header
 * actions render into `data-testid="chain-header-slot"`. Holds one registration
 * at a time behind a generation guard, as `ChainPage` does.
 */
export function ChainHeaderActionsTestSlot({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  const [header, setHeader] = useState<React.ReactNode>(null);
  const registrationRef = useRef(0);
  const registerHeaderActions = useCallback((actions: React.ReactNode) => {
    const generation = ++registrationRef.current;
    setHeader(actions);
    return () => {
      if (registrationRef.current === generation) {
        setHeader(null);
      }
    };
  }, []);
  const contextValue = useMemo(
    () => ({ registerHeaderActions }),
    [registerHeaderActions],
  );
  return (
    <ChainHeaderActionsContextProvider value={contextValue}>
      {children}
      <div data-testid="chain-header-slot">{header}</div>
    </ChainHeaderActionsContextProvider>
  );
}

/** Exported for tests that need `rerender` with the same Modals + header context. */
export function ChainHeaderTestRoot({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  return (
    <TestQueryClientProvider>
      <Modals>
        <ChainHeaderActionsTestSlot>{children}</ChainHeaderActionsTestSlot>
      </Modals>
    </TestQueryClientProvider>
  );
}

export function renderPageWithChainHeader(
  page: React.ReactElement,
): RenderResult {
  return render(<ChainHeaderTestRoot>{page}</ChainHeaderTestRoot>);
}
