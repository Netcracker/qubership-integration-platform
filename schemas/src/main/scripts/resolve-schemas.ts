import * as path from "path";
import * as fs from "fs";
import $RefParser from "@apidevtools/json-schema-ref-parser";
// @ts-ignore
import yaml from "js-yaml";

const inputDir = process.cwd() + "/src/main/resources/qip-model";
const outputDir = process.cwd() + "/target/resolved-schemas";

function collectYamlFiles(dir: string): string[] {
    let results: string[] = [];

    for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
        const fullPath = path.join(dir, entry.name);

        if (entry.isDirectory()) {
            results = results.concat(collectYamlFiles(fullPath));
        } else if (entry.isFile() && (entry.name.endsWith(".yaml") || entry.name.endsWith(".schema.yaml"))) {

            results.push(path.relative(inputDir, fullPath));
        }
    }

    return results;
}

async function resolveSchemaFile(filename: string) {
    const fullPath = path.join(inputDir, filename);
    const schema = await $RefParser.dereference(fullPath);
    const outputPath = path.join(outputDir, filename);

    fs.mkdirSync(path.dirname(outputPath), {recursive: true});
    fs.writeFileSync(outputPath, yaml.dump(schema));
}

async function main() {
    const files = collectYamlFiles(inputDir);

    for (const file of files) {
        await resolveSchemaFile(file);
    }
}

main().catch(err => {
    console.error("Error during schema resolving:", err);
    process.exit(1);
});
