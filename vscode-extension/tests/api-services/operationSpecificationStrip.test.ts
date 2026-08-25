// The writer strips an operation's specification slice only when reading can
// rebuild it from the raw source. Gating both on one predicate keeps the strip
// and the rebuild from drifting into two protocol lists, which is how the
// backend states it in ServiceSerializer.stripOperationSpecifications.

import { OperationSchemaExtractor } from "../../src/web/api-services/parsers/OperationSchemaExtractor";

describe("OperationSchemaExtractor.canRebuildSpecification", () => {
  test.each(["openapi", "asyncapi", "protobuf"])(
    "%s can be rebuilt, so the slice is redundant in the file",
    (type) => {
      expect(OperationSchemaExtractor.canRebuildSpecification(type)).toBe(true);
    },
  );

  // wsdl carries no schemas by design; graphql has no extractor path here, even
  // though runtime-catalog can rebuild it. Both keep the stored slice.
  test.each(["wsdl", "graphql"])("%s keeps the stored slice", (type) => {
    expect(OperationSchemaExtractor.canRebuildSpecification(type)).toBe(false);
  });

  test.each([undefined, "", "something-else"])(
    "%p is treated as not rebuildable",
    (type) => {
      expect(OperationSchemaExtractor.canRebuildSpecification(type)).toBe(
        false,
      );
    },
  );
});
