import * as path from "path";
import * as fs from "fs";
import $RefParser from "@apidevtools/json-schema-ref-parser";
// @ts-ignore
import yaml from "js-yaml";

const qubershipResolver = {
  order: 2,
  canRead: (file: any) => {
    return file.url.startsWith("http://qubership.org/schemas/product/qip/");
  },
  read(file: any) {
    const relPath = file.url.replace(
      "http://qubership.org/schemas/product/qip/",
      "",
    );

    const absPath = path.resolve(
      process.cwd(),
      "src/main/resources/qip-model/",
      relPath,
    );

    const content = fs.readFileSync(absPath, "utf-8");

    if (absPath.endsWith(".yaml")) {
      return yaml.load(content);
    }
  },
};

const ignoreSchemaResolver = {
  order: 0,
  canRead: (file: any) => file.url === "http://json-schema.org/draft-07/schema",
  read: (file: any) => {
    return file.url;
  },
};

const ignoreMapperResolver = {
  order: 1,
  canRead: (file: any) =>
    file.url ===
    "http://qubership.org/schemas/product/qip/element/properties/mapper-description.schema.yaml",
  read: (file: any) => {
    return {
      type: "object",
    };
  },
};

export class SchemaResolver {
  private inputDir = path.resolve(
    process.cwd(),
    "src/main/resources/qip-model/element",
  );
  private outputDir = path.resolve(process.cwd(), "assets");

  public async resolveAllSchemas(): Promise<void> {
    const files = this.collectYamlFiles(this.inputDir);

    for (const file of files) {
      await this.resolveSchemaFile(file);
    }
  }

  private collectYamlFiles(dir: string): string[] {
    let results: string[] = [];

    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const fullPath = path.join(dir, entry.name);

      if (
        entry.isFile() &&
        entry.name !== "element.schema.yaml" &&
        (entry.name.endsWith(".yaml") || entry.name.endsWith(".schema.yaml"))
      ) {
        results.push(path.relative(this.inputDir, fullPath));
      }
    }

    return results;
  }

  private inlineNestedRefs(obj: any): void {
    if (typeof obj !== "object" || obj === null) return;

    if ("$ref" in obj) {
      delete obj.$ref;
      obj.type = "object";
    }

    for (const key of Object.keys(obj)) {
      this.inlineNestedRefs(obj[key]);
    }
  }

  private removeNestedIds(obj: any, isRoot: boolean = true): void {
    if (typeof obj !== "object" || obj === null) return;

    if ("$id" in obj && !isRoot) {
      delete obj.$id;
    }

    for (const key of Object.keys(obj)) {
      this.removeNestedIds(obj[key], false);
    }
  }

  private async resolveSchemaFile(filename: string): Promise<void> {
    const fullPath = path.join(this.inputDir, filename);

    const rawContent = fs.readFileSync(fullPath, "utf-8");
    const schema = yaml.load(rawContent);

    const derefSchema = await $RefParser.dereference(schema, {
      resolve: {
        ignoreSchema: ignoreSchemaResolver,
        qubership: qubershipResolver,
        ignoreMapperResolver: ignoreMapperResolver,
        file: true,
        http: false,
      },

      dereference: {
        excludedPathMatcher: (path: string) => {
          return path.includes("/properties/children/items");
        },
        onCircular: (refPath: string) => {
          console.warn("ERROR", refPath);
        },
      },
    });

    this.inlineNestedRefs(derefSchema);
    this.removeNestedIds(derefSchema);
    const outputPath = path.join(this.outputDir, filename);

    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.writeFileSync(outputPath, yaml.dump(derefSchema));
  }
}
