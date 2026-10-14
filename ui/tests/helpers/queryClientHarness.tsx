import React, { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

/**
 * The client the app mounts at its root, for the suites that render a screen
 * reading a cached query. One client per mount, so nothing cached outlives the
 * test that read it.
 */
export function TestQueryClientProvider({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  const [queryClient] = useState(() => new QueryClient());
  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}
