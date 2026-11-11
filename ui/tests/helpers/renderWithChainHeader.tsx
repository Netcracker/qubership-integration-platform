import React, { useState } from "react";
import { render, type RenderResult } from "@testing-library/react";
import { Modals } from "../../src/Modals.tsx";
import { ChainHeaderSlotProvider } from "../../src/pages/ChainHeaderActionsContext.tsx";
import { TestQueryClientProvider } from "./queryClientHarness.tsx";

/**
 * Wraps chain tab pages so header actions render into
 * `data-testid="chain-header-slot"`, as `ChainPage` provides its header slot.
 */
export function ChainHeaderActionsTestSlot({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  const [slot, setSlot] = useState<HTMLElement | null>(null);
  return (
    <ChainHeaderSlotProvider value={slot}>
      {children}
      <div data-testid="chain-header-slot" ref={setSlot} />
    </ChainHeaderSlotProvider>
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
