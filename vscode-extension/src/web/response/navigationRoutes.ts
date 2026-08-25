// The webview navigation paths, and which kind of file each one resolves to. Kept apart from
// `apiRouter` so the file layer can name them without pulling the whole message dispatch in.

export const SERVICE_ROUTES: RegExp[] = [
  /^\/services\/systems\/[^/]+\/parameters$/,
  /^\/services\/systems\/[^/]+\/specificationGroups$/,
  /^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications$/,
  /^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications\/[^/]+$/,
  /^\/services\/systems\/[^/]+\/environments$/,
  /^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications\/[^/]+\/operations$/,
  /^\/services\/systems\/[^/]+\/specificationGroups\/[^/]+\/specifications\/[^/]+\/operations\/[^/]+$/,
];

export const CONTEXT_SERVICE_ROUTES: RegExp[] = [
  /^\/services\/context\/[^/]+\/parameters$/,
];

export const MCP_SERVICE_ROUTES: RegExp[] = [
  /^\/services\/mcp\/[^/]+\/parameters$/,
];

export const CHAIN_ROUTES: RegExp[] = [/^\/chains\/[^/]+(?:\/.*)?$/];

export const ROUTES: RegExp[] = [...SERVICE_ROUTES, ...CHAIN_ROUTES];
