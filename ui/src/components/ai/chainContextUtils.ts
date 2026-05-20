import { Chain, Element } from "../../api/apiTypes.ts";

export interface CompactChainSchema {
  chainId: string;
  chainName: string;
  elements: Array<{
    id: string;
    name: string;
    type: string;
    serviceId?: string;
    operationId?: string;
    protocol?: string;
    parentElementId?: string;
  }>;
  connections: Array<{
    from: string;
    to: string;
  }>;
}

/**
 * Creates a compact schema of the chain for AI context.
 * Includes only essential information: element IDs, names, types, and connections.
 * For detailed information, LLM should use MCP tools.
 */
export function createCompactChainSchema(chain: Chain): CompactChainSchema {
  const elements = (chain.elements || []).map((element: Element) => {
    const properties = (
      element as unknown as { properties?: Record<string, unknown> }
    ).properties;

    return {
      id: element.id,
      name: element.name || element.id,
      type: element.type,
      serviceId: properties?.["integrationSystemId"] as string | undefined,
      operationId: properties?.["integrationOperationId"] as string | undefined,
      protocol: properties?.["integrationOperationProtocolType"] as
        | string
        | undefined,
      parentElementId: element.parentElementId,
    };
  });

  const connections = (chain.dependencies || []).map((dep) => ({
    from: dep.from,
    to: dep.to,
  }));

  return {
    chainId: chain.id,
    chainName: chain.name,
    elements,
    connections,
  };
}

/**
 * Formats compact chain schema as a readable string for LLM context
 */
export function formatCompactChainSchema(schema: CompactChainSchema): string {
  const parts: string[] = [];

  parts.push(`**Current Chain:** ${schema.chainName} (ID: ${schema.chainId})`);
  parts.push(
    `\n**IMPORTANT:** When the user says "this chain", "the chain", or refers to the chain without specifying its name or ID, they mean chainId=${schema.chainId}.`,
  );

  parts.push(`\n**Chain Graph Schema:**`);
  parts.push(`\nElements (${schema.elements.length}):`);

  schema.elements.forEach((element, index) => {
    const elementParts: string[] = [];
    elementParts.push(
      `  ${index + 1}. [${element.id}] ${element.name} (${element.type})`,
    );

    if (element.serviceId) {
      elementParts.push(`      Service ID: ${element.serviceId}`);
    }
    if (element.operationId) {
      elementParts.push(`      Operation ID: ${element.operationId}`);
    }
    if (element.protocol) {
      elementParts.push(`      Protocol: ${element.protocol}`);
    }
    if (element.parentElementId) {
      elementParts.push(`      Parent: ${element.parentElementId}`);
    }

    parts.push(elementParts.join("\n"));
  });

  if (schema.connections.length > 0) {
    parts.push(`\nConnections (${schema.connections.length}):`);
    schema.connections.forEach((conn, index) => {
      const fromElement = schema.elements.find((e) => e.id === conn.from);
      const toElement = schema.elements.find((e) => e.id === conn.to);
      const fromName = fromElement?.name || conn.from;
      const toName = toElement?.name || conn.to;
      parts.push(
        `  ${index + 1}. ${fromName} [${conn.from}] → ${toName} [${conn.to}]`,
      );
    });
  }

  parts.push(`\n**Usage Instructions:**`);
  parts.push(
    `- Use this schema to understand the chain structure and element relationships.`,
  );
  parts.push(
    `- When user asks about specific elements, use the element IDs from this schema.`,
  );
  parts.push(
    `- For detailed element information (properties, scripts, mappings), use MCP tool catalog.get_chain with chainId=${schema.chainId}.`,
  );
  parts.push(
    `- For specific operation details, use catalog.get_operation with operationId from the schema.`,
  );
  parts.push(
    `- For specific service details, use catalog.get_system with systemId from the schema.`,
  );

  return parts.join("\n");
}

/**
 * Legacy function - kept for backward compatibility
 * @deprecated Use createCompactChainSchema and formatCompactChainSchema instead
 */
export function formatChainForAiContext(chain: Chain): string {
  const schema = createCompactChainSchema(chain);
  return formatCompactChainSchema(schema);
}

export function getChainContextPrompt(chain: Chain): string {
  const schema = createCompactChainSchema(chain);
  const chainInfo = formatCompactChainSchema(schema);
  return chainInfo;
}
