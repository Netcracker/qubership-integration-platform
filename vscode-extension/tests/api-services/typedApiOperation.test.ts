// The extension and runtime-catalog must write the same per-protocol operation
// shape, or a file one tool writes loses method/path when the other imports it.
// toTypedApiOperation is the extension's writer; these tests assert its output
// matches the backend's ApiOperationDto field-for-field and that the backend's
// derivation (mirrored by deriveTypedMethodPath) reconstructs method/path from
// it — the extension-write -> backend-import round trip.

import { toTypedApiOperation } from "../../src/web/api-services/typedApiOperation";
import {
  deriveMethod,
  derivePath,
  isTypedOperation,
} from "../../src/web/api-services/parsers/deriveTypedMethodPath";
import { GraphQLSpecificationParser } from "../../src/web/api-services/parsers/GraphQLSpecificationParser";
import { ProtoSpecificationParser } from "../../src/web/api-services/parsers/ProtoSpecificationParser";

function keys(value: Record<string, unknown>): string[] {
  return Object.keys(value).sort();
}

describe("toTypedApiOperation - per-protocol backend shape", () => {
  test("openapi carries type, path, and a lowercased method", () => {
    const written = toTypedApiOperation(
      {
        id: "op-1",
        name: "getPet",
        method: "GET",
        path: "/pets/{id}",
        specification: { summary: "x" },
      },
      "openapi",
    );

    expect(written).toMatchObject({
      id: "op-1",
      name: "getPet",
      type: "openapi",
      method: "get",
      path: "/pets/{id}",
    });
    expect(isTypedOperation(written)).toBe(true);
    expect(deriveMethod(written as never)).toBe("GET");
    expect(derivePath(written as never)).toBe("/pets/{id}");
  });

  test("asyncapi carries channel and method, not a flat path", () => {
    const written = toTypedApiOperation(
      {
        id: "op-a",
        name: "onOrder",
        method: "publish",
        path: "orders/created",
      },
      "asyncapi",
    );

    expect(keys(written)).toEqual(["channel", "id", "method", "name", "type"]);
    expect(written.channel).toBe("orders/created");
    expect(written).not.toHaveProperty("path");
    expect(derivePath(written as never)).toBe("orders/created");
  });

  test("graphql carries operationType and sdl, not method/path", () => {
    const written = toTypedApiOperation(
      {
        id: "op-g",
        name: "customer",
        method: "query",
        path: "customer(id: ID!): Customer",
        operationType: "query",
        sdl: "customer(id: ID!): Customer",
        specification: { operation: "customer(id: ID!): Customer" },
      },
      "graphql",
    );

    expect(keys(written)).toEqual([
      "id",
      "name",
      "operationType",
      "sdl",
      "specification",
      "type",
    ]);
    expect(written).not.toHaveProperty("method");
    expect(written).not.toHaveProperty("path");
    // A backend import reconstructs path from sdl, method from operationType.
    expect(derivePath(written as never)).toBe("customer(id: ID!): Customer");
    expect(deriveMethod(written as never)).toBe("query");
  });

  test("protobuf carries package, service, rpcMethod, and javaPackage", () => {
    const written = toTypedApiOperation(
      {
        id: "op-p",
        name: "PaymentService.Authorize",
        method: "Authorize",
        path: "com.acme.payments.grpc.PaymentService",
        package: "acme.payments.v1",
        service: "PaymentService",
        rpcMethod: "Authorize",
        javaPackage: "com.acme.payments.grpc",
      },
      "protobuf",
    );

    expect(keys(written)).toEqual([
      "id",
      "javaPackage",
      "name",
      "package",
      "rpcMethod",
      "service",
      "type",
    ]);
    expect(written).not.toHaveProperty("path");
    // path derives from javaPackage (differs from the proto package), not package.
    expect(derivePath(written as never)).toBe(
      "com.acme.payments.grpc.PaymentService",
    );
    expect(deriveMethod(written as never)).toBe("Authorize");
  });

  test("wsdl omits the protocol/binding the parser cannot produce (deviation)", () => {
    const written = toTypedApiOperation(
      { id: "op-w", name: "sayHello", method: "POST", path: "" },
      "wsdl",
    );

    // The extension's WSDL parser yields no protocol/binding, so only the type
    // rides. method/path still reconstruct to the constant POST / "".
    expect(keys(written)).toEqual(["id", "name", "type"]);
    expect(deriveMethod(written as never)).toBe("POST");
    expect(derivePath(written as never)).toBe("");
  });

  test("omits a null specification, matching the backend NON_NULL export", () => {
    const written = toTypedApiOperation(
      { id: "op-w", name: "sayHello", specification: null },
      "wsdl",
    );
    expect(written).not.toHaveProperty("specification");
  });
});

describe("parsers attach the fields the api writer needs", () => {
  test("graphql parser output writes a lossless graphql operation", async () => {
    const data = await GraphQLSpecificationParser.parseGraphQLContent(
      "type Query { customer(id: ID!): Customer }\ntype Customer { id: ID! }",
    );
    const [operation] = GraphQLSpecificationParser.createOperationsFromGraphQL(
      data,
      "spec-1",
    );

    const written = toTypedApiOperation(operation, "graphql");
    expect(written.type).toBe("graphql");
    expect(written.operationType).toBe("query");
    expect(written.sdl).toBe(operation.path);
    expect(derivePath(written as never)).toBe(operation.path);
  });

  test("proto parser output writes a lossless protobuf operation", async () => {
    const proto = [
      'syntax = "proto3";',
      "package acme.payments.v1;",
      'option java_package = "com.acme.payments.grpc";',
      "message PayRequest { string id = 1; }",
      "message PayResponse { string status = 1; }",
      "service PaymentService { rpc Authorize(PayRequest) returns (PayResponse); }",
    ].join("\n");
    const data = await ProtoSpecificationParser.parseProtoContent(proto);
    const [operation] = ProtoSpecificationParser.createOperationsFromProto(
      data,
      "spec-1",
    );

    const written = toTypedApiOperation(operation, "protobuf");
    expect(written).toMatchObject({
      type: "protobuf",
      package: "acme.payments.v1",
      service: "PaymentService",
      rpcMethod: "Authorize",
      javaPackage: "com.acme.payments.grpc",
    });
    // The backend reconstructs path from javaPackage, not the proto package.
    expect(derivePath(written as never)).toBe(
      "com.acme.payments.grpc.PaymentService",
    );
  });
});
