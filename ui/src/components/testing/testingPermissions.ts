import { RequiredPermissions } from "../../permissions/types.ts";

/** Rights a testing action needs, resolved once so every button asks the same question. */
export type TestingPermissions = {
  view: RequiredPermissions;
  write: RequiredPermissions;
  execute: RequiredPermissions;
  import: RequiredPermissions;
  export: RequiredPermissions;
};

const CHAIN_SCOPE: TestingPermissions = {
  view: { chain: ["read"] },
  write: { chain: ["update"] },
  execute: { chain: ["execute"] },
  import: { chain: ["import"] },
  export: { chain: ["export"] },
};

const ADMIN_SCOPE: TestingPermissions = {
  view: { adminTools: ["read"] },
  write: { adminTools: ["update"] },
  execute: { adminTools: ["execute"] },
  import: { adminTools: ["import"] },
  export: { adminTools: ["export"] },
};

/** Screens opened inside a chain are gated by the chain; the rest by admin tools. */
export function getTestingPermissions(chainId?: string): TestingPermissions {
  return chainId ? CHAIN_SCOPE : ADMIN_SCOPE;
}
