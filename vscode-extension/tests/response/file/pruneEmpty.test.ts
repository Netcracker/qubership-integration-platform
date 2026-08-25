// Every service save runs through pruneEntity. Without it the read-path
// normalizer's defaults — `description: ""`, `labels: []`, `properties: {}` —
// are written back into the file, which the backend export never contains.

import {
  pruneEmpty,
  pruneEntity,
} from "../../../src/web/response/file/pruneEmpty";

describe("pruneEmpty", () => {
  it("drops blanks and empty containers", () => {
    expect(
      pruneEmpty({
        name: "svc",
        description: "",
        labels: [],
        properties: {},
        missing: null,
        absent: undefined,
      }),
    ).toEqual({ name: "svc" });
  });

  it("keeps false and zero", () => {
    expect(pruneEmpty({ deprecated: false, retries: 0 })).toEqual({
      deprecated: false,
      retries: 0,
    });
  });

  it("prunes nested objects and array items", () => {
    expect(
      pruneEmpty({
        environments: [
          { id: "e1", labels: [], properties: { soTimeout: "120000" } },
          { id: "e2", description: "" },
        ],
      }),
    ).toEqual({
      environments: [
        { id: "e1", properties: { soTimeout: "120000" } },
        { id: "e2" },
      ],
    });
  });

  it("drops a nested object that prunes to nothing", () => {
    expect(pruneEmpty({ outer: { inner: { blank: "" } } })).toEqual({});
  });

  // EnvironmentDefaultProperties seeds kafka with seven blank-valued keys and
  // amqp with three. They are the caller's values, not absent ones, so the
  // backend keeps content inclusion at NON_NULL and so does this.
  it("keeps blank values inside a free-form properties map", () => {
    expect(
      pruneEmpty({
        properties: {
          key: "",
          securityProtocol: "",
          maxPollRecords: "500",
          unset: null,
        },
      }),
    ).toEqual({
      properties: { key: "", securityProtocol: "", maxPollRecords: "500" },
    });
  });

  it("still drops the properties map when it is empty", () => {
    expect(pruneEmpty({ id: "e1", properties: {} })).toEqual({ id: "e1" });
  });
});

describe("pruneEntity", () => {
  it("keeps content even when it prunes to nothing", () => {
    expect(pruneEntity({ id: "s1", content: { description: "" } })).toEqual({
      id: "s1",
      content: {},
    });
  });

  it("does not invent content for an entity that has none", () => {
    expect(pruneEntity({ id: "s1" })).toEqual({ id: "s1" });
  });

  it("keeps the migrations claim, which is never empty", () => {
    expect(
      pruneEntity({
        id: "s1",
        content: { migrations: "[100, 101]", labels: [] },
      }),
    ).toEqual({ id: "s1", content: { migrations: "[100, 101]" } });
  });
});
