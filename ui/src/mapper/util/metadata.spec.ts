import { MetadataUtil } from "./metadata.ts";
import { Metadata, MetadataAware } from "../model/model.ts";

describe("Mapper", () => {
  describe("MetadataUtil", () => {
    describe("upsert", () => {
      it("should return object that has only specified key-value pair if metadata is falsy", () => {
        ([undefined, null] as unknown as Metadata[]).forEach((metadata: Metadata) => {
          expect(MetadataUtil.upsert(metadata, "foo", "bar"), String(metadata as unknown))
            .toEqual({ foo: "bar" });
        });
      });

      it("should override existing value with given value", () => {
        expect(
          MetadataUtil.upsert({ foo: "bar", baz: "biz" }, "foo", "fiz"),
        ).toEqual({
          foo: "fiz",
          baz: "biz",
        });
      });

      it("should return object having all key-value pairs from metadata and specified key-value pair", () => {
        expect(
          MetadataUtil.upsert({ foo: "bar", baz: "biz" }, "fiz", "qux"),
        ).toEqual({
          foo: "bar",
          baz: "biz",
          fiz: "qux",
        });
      });
    });

    describe("getValue", () => {
      it("should return undefined value when object is undefined or null", () => {
        ([undefined, null] as unknown as MetadataAware[]).forEach((obj: MetadataAware) => {
          expect(MetadataUtil.getValue(obj, "foo"), String(obj as unknown))
            .toBeUndefined();
        });
      });

      it("should return undefined value when metadata is undefined or null", () => {
        ([undefined, null] as unknown as Metadata[]).forEach((metadata: Metadata) => {
          expect(MetadataUtil.getValue({ metadata }, "foo"), String(metadata as unknown))
            .toBeUndefined();
        });
      });

      it("should return undefined value when key is not in metadata", () => {
        expect(
          MetadataUtil.getValue({ metadata: { foo: "bar" } }, "baz"),
        ).toBeUndefined();
      });

      it("should return corresponding value when key is in metadata", () => {
        expect(
          MetadataUtil.getValue({ metadata: { foo: "bar" } }, "foo"),
        ).toEqual("bar");
      });
    });

    describe("setValue", () => {
      it("should return undefined value when object is undefined", () => {
        expect(MetadataUtil.setValue(undefined, "foo", "bar")).toBeUndefined();
      });

      it("should return null value when object is null", () => {
        expect(MetadataUtil.setValue(null, "foo", "bar")).toBeNull();
      });

      it("should return object with existing value in metadata being overridden", () => {
        expect(
          MetadataUtil.setValue(
            { a: "b", metadata: { foo: "bar", baz: "biz" } },
            "foo",
            "fiz",
          ),
        ).toEqual({
          a: "b",
          metadata: { foo: "fiz", baz: "biz" },
        });
      });

      it("should add specified key-value pair to the returning object metadata", () => {
        expect(
          MetadataUtil.setValue<{ a: string; metadata: Metadata }, string>(
            { a: "b", metadata: { foo: "bar" } },
            "baz",
            "biz",
          ),
        ).toEqual({
          a: "b",
          metadata: { foo: "bar", baz: "biz" },
        });
      });
    });
  });
});
