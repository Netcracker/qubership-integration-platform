import React, { useState } from "react";
import { render, type RenderResult } from "@testing-library/react";
import { Modals } from "../../src/Modals.tsx";
import { ChainHeaderActionsContextProvider } from "../../src/pages/ChainHeaderActionsContext.tsx";

/**
 * Wraps chain tab pages so `useRegisterChainHeaderActions` runs and header
 * actions render into `data-testid="chain-header-slot"`.
 */
/** Exported for tests that need `rerender` with the same Modals + header context. */
export function ChainHeaderTestRoot({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  const [header, setHeader] = useState<React.ReactNode>(null);
  return (
    <Modals>
      <ChainHeaderActionsContextProvider value={{ setActions: setHeader }}>
        {children}
        <div data-testid="chain-header-slot">{header}</div>
      </ChainHeaderActionsContextProvider>
    </Modals>
  );
}

export function renderPageWithChainHeader(
  page: React.ReactElement,
): RenderResult {
  return render(<ChainHeaderTestRoot>{page}</ChainHeaderTestRoot>);
}
