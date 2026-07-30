// TypeScript mirror of the backend's TypedOperation.deriveMethod / derivePath
// (runtime-catalog model.system.typed.*Operation). A `.api` file exported by
// runtime-catalog stores only the typed discriminated fields; for non-openapi
// protocols it carries no flat `method`/`path`, so both must be re-derived the
// same way the backend derives its `method`/`path` columns. Shared by the read
// path (serviceApiRead.parseOperations) and the conformance parity oracle so
// there is one derivation, not two.

export type TypedOperation =
  | {
      type: "openapi";
      summary?: string | null;
      path: string;
      method: string;
      isDeprecated?: boolean | null;
    }
  | {
      type: "asyncapi";
      summary?: string | null;
      channel: string;
      method: string;
    }
  | {
      type: "protobuf";
      package?: string | null;
      service: string;
      rpcMethod: string;
      javaPackage?: string | null;
    }
  | { type: "graphql"; operationType: string; sdl?: string | null }
  | { type: "wsdl"; protocol?: string | null; binding?: string | null };

const TYPED_OPERATION_KINDS: ReadonlySet<string> = new Set([
  "openapi",
  "asyncapi",
  "protobuf",
  "graphql",
  "wsdl",
]);

// openapi stores its method lowercase (the schema enum) and derives it back
// uppercase; wsdl is the constant POST.
export function deriveMethod(typed: TypedOperation): string {
  switch (typed.type) {
    case "openapi":
      return (typed.method ?? "").toUpperCase();
    case "asyncapi":
      return typed.method;
    case "protobuf":
      return typed.rpcMethod;
    case "graphql":
      return typed.operationType;
    case "wsdl":
      return "POST";
  }
}

// protobuf joins the java package (falling back to the proto package) with the
// service; wsdl is the empty string; graphql is the SDL, which the file carries
// so path reconstructs. An older file that omits sdl derives to null here.
export function derivePath(typed: TypedOperation): string | null {
  switch (typed.type) {
    case "openapi":
      return typed.path;
    case "asyncapi":
      return typed.channel;
    case "protobuf": {
      // Mirror the backend ProtobufOperation.derivePath: a package-less proto3 file joins to just the service,
      // not the literal "null.<service>" (Java) or ".<service>" (Array.join on undefined).
      const pkg = typed.javaPackage ?? typed.package;
      return pkg != null ? `${pkg}.${typed.service}` : typed.service;
    }
    case "graphql":
      return typed.sdl ?? null;
    case "wsdl":
      return "";
  }
}

// A flat operation node is a TypedOperation once it carries a known `type`
// discriminator. Legacy `.specification` operations have no `type`, so they
// keep their own flat `method`/`path` and never derive.
export function isTypedOperation(value: unknown): value is TypedOperation {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as { type?: unknown }).type === "string" &&
    TYPED_OPERATION_KINDS.has((value as { type: string }).type)
  );
}
