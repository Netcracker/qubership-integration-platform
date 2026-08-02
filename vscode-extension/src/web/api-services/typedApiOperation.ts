// Write-side twin of deriveTypedMethodPath. Reshapes a parsed operation into the
// backend's per-protocol typed api shape (runtime-catalog ApiOperationDtoMapper.toDto):
// one place decides the discriminated shape, so a file the extension writes imports
// into runtime-catalog without losing method or path. Per protocol:
//   openapi   -> type, summary?, path, method (lowercase), isDeprecated?
//   asyncapi  -> type, summary?, channel, method
//   graphql   -> type, operationType, sdl
//   protobuf  -> type, package, service, rpcMethod, javaPackage
//   wsdl      -> type, protocol?, binding?  (see the deviation below)
//
// Deviation: the extension's WSDL parser derives no protocol or binding, so a wsdl
// operation carries only its type here. method and path stay constant (POST / "")
// and derive back on read, so path/method round-trip; protocol and binding do not.

import type { SystemOperation } from "./servicesTypes";

// The parsers attach fields beyond SystemOperation (notably specification), so
// accept a superset rather than the exact interface.
type WritableOperation = Partial<SystemOperation> & {
  id: string;
  name: string;
  specification?: unknown;
};

export function toTypedApiOperation(
  operation: WritableOperation,
  specificationType: string,
): Record<string, unknown> {
  const base: Record<string, unknown> = {
    id: operation.id,
    name: operation.name,
    type: specificationType,
  };
  if (operation.description) {
    base.description = operation.description;
  }
  // Match the backend's @JsonInclude(NON_NULL): a null specification (wsdl) is omitted.
  if (
    operation.specification !== undefined &&
    operation.specification !== null
  ) {
    base.specification = operation.specification;
  }

  switch (specificationType) {
    case "openapi":
      return withDefined(base, {
        summary: operation.summary,
        path: operation.path,
        method: lowercase(operation.method),
        isDeprecated: operation.isDeprecated,
      });
    case "asyncapi":
      return withDefined(base, {
        summary: operation.summary,
        channel: operation.channel ?? operation.path,
        method: operation.method,
      });
    case "graphql":
      return withDefined(base, {
        operationType: operation.operationType ?? operation.method,
        sdl: operation.sdl ?? operation.path,
      });
    case "protobuf":
      return withDefined(base, {
        package: operation.package,
        service: operation.service,
        rpcMethod: operation.rpcMethod ?? operation.method,
        javaPackage: operation.javaPackage,
      });
    case "wsdl":
      return withDefined(base, {
        protocol: operation.protocol,
        binding: operation.binding,
      });
    default:
      return withDefined(base, {
        path: operation.path,
        method: operation.method,
      });
  }
}

function withDefined(
  base: Record<string, unknown>,
  fields: Record<string, unknown>,
): Record<string, unknown> {
  const result = { ...base };
  for (const [key, value] of Object.entries(fields)) {
    if (value !== undefined) {
      result[key] = value;
    }
  }
  return result;
}

function lowercase(value: string | undefined): string | undefined {
  return typeof value === "string" ? value.toLowerCase() : value;
}
