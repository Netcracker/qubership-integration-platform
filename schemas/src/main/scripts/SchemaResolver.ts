import * as path from "path";
import * as fs from "fs";
import $RefParser from "@apidevtools/json-schema-ref-parser";
// @ts-ignore
import yaml from "js-yaml";

export class SchemaResolver {
    private inputDir = path.resolve(process.cwd(), "src/main/resources/qip-model");
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

            if (entry.isDirectory()) {
                results = results.concat(this.collectYamlFiles(fullPath));
            } else if (entry.isFile() && (entry.name.endsWith(".yaml") || entry.name.endsWith(".schema.yaml"))) {
                results.push(path.relative(this.inputDir, fullPath));
            }
        }

        return results;
    }

    private async resolveSchemaFile(filename: string): Promise<void> {
        const fullPath = path.join(this.inputDir, filename);
        const schema = await $RefParser.dereference(fullPath);
        const outputPath = path.join(this.outputDir, filename);

        fs.mkdirSync(path.dirname(outputPath), { recursive: true });
        fs.writeFileSync(outputPath, yaml.dump(schema));
    }
}
