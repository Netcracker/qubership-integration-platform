export const BROWSER_TAB_DEFAULT_TITLE = "QIP";

export const OPERATION_INFO_TAB_TITLE = "Operation info";

const SERVICES_HASH_TITLES: Record<string, string> = {
  external: "External Services",
  internal: "Inner Services",
  implemented: "Implemented Services",
  mcp: "MCP",
  context: "Context",
};

const ADMIN_PATH_TITLES: Array<{ prefix: string; title: string }> = [
  { prefix: "/admintools/variables/common", title: "Common Variables" },
  { prefix: "/admintools/variables/secured", title: "Secured Variables" },
  { prefix: "/admintools/domains", title: "Domains" },
  { prefix: "/admintools/audit", title: "Audit" },
  { prefix: "/admintools/sessions", title: "Sessions" },
  { prefix: "/admintools/access-control", title: "Roles" },
  { prefix: "/admintools/exchanges", title: "Live Exchanges" },
  { prefix: "/admintools/import-instructions", title: "Import Instructions" },
  {
    prefix: "/admintools/detailed-design/templates",
    title: "Design Templates",
  },
];

const DEV_PATH_TITLES: Array<{ prefix: string; title: string }> = [
  { prefix: "/devtools/maas/kafka", title: "Kafka" },
  { prefix: "/devtools/maas/rabbitmq", title: "RabbitMQ" },
  { prefix: "/devtools/diagnostic", title: "Diagnostic" },
];

export type ParsedServiceRoute = {
  systemId?: string;
  groupId?: string;
  specId?: string;
  operationId?: string;
  variant: "systems" | "context" | "mcp" | "list";
};

export function normalizeServicesHash(hash: string): string {
  const value = hash.replace(/^#/, "").trim();
  return value || "external";
}

export function getServicesListTabTitle(hash: string): string {
  const key = normalizeServicesHash(hash);
  return SERVICES_HASH_TITLES[key] ?? SERVICES_HASH_TITLES.external;
}

export function parseServiceRoute(pathname: string): ParsedServiceRoute | null {
  if (/^\/services\/?$/.test(pathname)) {
    return { variant: "list" };
  }

  const contextMatch = pathname.match(/^\/services\/context\/([^/]+)/);
  if (contextMatch) {
    return { variant: "context", systemId: contextMatch[1] };
  }

  const mcpMatch = pathname.match(/^\/services\/mcp\/([^/]+)/);
  if (mcpMatch) {
    return { variant: "mcp", systemId: mcpMatch[1] };
  }

  const systemMatch = pathname.match(/^\/services\/systems\/([^/]+)/);
  if (!systemMatch) {
    return null;
  }

  const systemId = systemMatch[1];
  const groupMatch = pathname.match(/\/specificationGroups\/([^/]+)/);
  const specMatch = pathname.match(/\/specifications\/([^/]+)/);
  const operationMatch = pathname.match(/\/operations\/([^/]+)/);

  return {
    variant: "systems",
    systemId,
    groupId: groupMatch?.[1],
    specId: specMatch?.[1],
    operationId: operationMatch?.[1],
  };
}

function matchPathTitle(
  pathname: string,
  rules: Array<{ prefix: string; title: string }>,
): string | null {
  for (const { prefix, title } of rules) {
    if (pathname === prefix || pathname.startsWith(`${prefix}/`)) {
      return title;
    }
  }
  return null;
}

export function getStaticBrowserTabTitle(
  pathname: string,
  hash: string,
): string | null {
  if (pathname === "/" || pathname === "/chains") {
    return "Chains";
  }

  if (pathname.startsWith("/doc")) {
    return "Helper";
  }

  const servicesRoute = parseServiceRoute(pathname);
  if (servicesRoute?.variant === "list") {
    return getServicesListTabTitle(hash);
  }

  if (pathname.startsWith("/admintools")) {
    if (pathname === "/admintools" || pathname === "/admintools/") {
      return "Admin Tools";
    }
    return matchPathTitle(pathname, ADMIN_PATH_TITLES) ?? "Admin Tools";
  }

  if (pathname.startsWith("/devtools")) {
    if (pathname === "/devtools" || pathname === "/devtools/") {
      return "Dev Tools";
    }
    return matchPathTitle(pathname, DEV_PATH_TITLES) ?? "Dev Tools";
  }

  return null;
}

export function extractChainId(pathname: string): string | null {
  const match = pathname.match(/^\/chains\/([^/]+)/);
  if (!match) {
    return null;
  }
  const chainId = match[1];
  if (chainId === "diff") {
    return null;
  }
  return chainId;
}

export function hasOpenApiOperationId(
  specification: Record<string, unknown> | undefined,
): boolean {
  if (!specification) {
    return false;
  }
  const operationId = specification.operationId;
  return typeof operationId === "string" && operationId.trim().length > 0;
}

export function resolveOperationTabTitle(
  operationName: string | undefined,
  specification: Record<string, unknown> | undefined,
): string {
  if (!hasOpenApiOperationId(specification)) {
    return OPERATION_INFO_TAB_TITLE;
  }
  return operationName?.trim() || OPERATION_INFO_TAB_TITLE;
}
