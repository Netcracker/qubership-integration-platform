import { ResourceFileNames } from "../../src/web/response/ResourceFileNames";

describe("ResourceFileNames", () => {
  describe("empty", () => {
    it("returns undefined for every accessor", () => {
      const names = ResourceFileNames.empty();

      expect(names.getGeneric()).toBeUndefined();
      expect(names.getBefore()).toBeUndefined();
      expect(names.getAfter("script", "404")).toBeUndefined();
    });
  });

  describe("fromElement", () => {
    it("reads the generic propertiesFilename", () => {
      const element = {
        properties: {
          propertiesFilename: "el-1.element.mapper.cip.json",
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getGeneric()).toBe("el-1.element.mapper.cip.json");
    });

    it("reads the before propertiesFilename", () => {
      const element = {
        properties: {
          before: { propertiesFilename: "el-1.before.script.cip.groovy" },
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getBefore()).toBe("el-1.before.script.cip.groovy");
    });

    it("reads after filenames keyed by type and id", () => {
      const element = {
        properties: {
          after: [
            { type: "script", id: "404", propertiesFilename: "el-1.after-404.script.cip.groovy" },
            { type: "mapper", id: "200", propertiesFilename: "el-1.after-200.mapper.cip.json" },
          ],
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("script", "404")).toBe("el-1.after-404.script.cip.groovy");
      expect(names.getAfter("mapper", "200")).toBe("el-1.after-200.mapper.cip.json");
    });

    it("falls back to code when id is absent for after block key", () => {
      const element = {
        properties: {
          after: [{ type: "script", code: "myCode", propertiesFilename: "el-1.after-myCode.script.cip.groovy" }],
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("script", "myCode")).toBe("el-1.after-myCode.script.cip.groovy");
    });

    it("ignores after blocks without a propertiesFilename", () => {
      const element = {
        properties: {
          after: [{ type: "script", id: "404" }],
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("script", "404")).toBeUndefined();
    });

    it("ignores after blocks with an empty propertiesFilename", () => {
      const element = {
        properties: {
          after: [{ type: "script", id: "404", propertiesFilename: "" }],
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("script", "404")).toBeUndefined();
    });

    it("returns undefined for an unknown after key", () => {
      const element = {
        properties: {
          after: [{ type: "script", id: "404", propertiesFilename: "el-1.after-404.script.cip.groovy" }],
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("mapper", "404")).toBeUndefined();
      expect(names.getAfter("script", "500")).toBeUndefined();
    });

    it("returns undefined when properties is missing", () => {
      const element = {} as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getGeneric()).toBeUndefined();
      expect(names.getBefore()).toBeUndefined();
      expect(names.getAfter("script", "404")).toBeUndefined();
    });

    it("returns undefined when after is not an array", () => {
      const element = {
        properties: {
          after: null,
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("script", "404")).toBeUndefined();
    });

    it("handles undefined after array gracefully", () => {
      const element = {
        properties: {},
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("script", "404")).toBeUndefined();
    });

    it("distinguishes after entries by type even with the same id", () => {
      const element = {
        properties: {
          after: [
            { type: "script", id: "200", propertiesFilename: "el-1.after-200.script.cip.groovy" },
            { type: "mapper", id: "200", propertiesFilename: "el-1.after-200.mapper.cip.json" },
          ],
        },
      } as any;

      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("script", "200")).toBe("el-1.after-200.script.cip.groovy");
      expect(names.getAfter("mapper", "200")).toBe("el-1.after-200.mapper.cip.json");
    });

    it("stores a 2xx range key verbatim from the stored filename", () => {
      const element = {
        properties: {
          after: [{ type: "script", id: "200..299", propertiesFilename: "el-1.after-2xx.script.cip.groovy" }],
        },
      } as any;

      // The stored after block keeps the original id (200..299) as key; lookup must use the same id form
      const names = ResourceFileNames.fromElement(element);

      expect(names.getAfter("script", "200..299")).toBe("el-1.after-2xx.script.cip.groovy");
      expect(names.getAfter("script", "2xx")).toBeUndefined();
    });
  });
});
