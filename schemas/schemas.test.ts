import Ajv from "ajv";
import { walkSync } from "@nodelib/fs.walk";
import { parse as parseYaml } from "yaml";
import * as fs from "node:fs";

type Document = {
    path: string;
    content: string;
}

function getDocuments(directory: string, suffix: string): Document[] {
    return walkSync(directory)
        .filter(entry => entry.path.endsWith(suffix))
        .map(entry => entry.path)
        .map(path => ({
            path,
            content: fs.readFileSync(path, "utf8")
        }));
}

function getSchemas(): any[] {
    return getDocuments("src/main/resources/qip-model", ".schema.yaml");
}

function getSamples(): any[] {
    return getDocuments("src/test/resources/samples", ".yaml");
}

function getSchema(content: string): string {
    return content.split("\n")[0]
        ?.replace(/#\s*\$schema:\s*/, "")
        .trim();
}

test("Test schemas conformance", () => {
    const ajv = new Ajv({
        verbose: true,
        allErrors: true,
    });
    const draft7Schema = ajv.getSchema("http://json-schema.org/draft-07/schema");
    expect(draft7Schema).toBeDefined();
    getSchemas()
        .map(document => document.content)
        .map(content => parseYaml(content))
        .forEach(schema => {
            ajv.addSchema(schema, schema["$id"]);
            expect(draft7Schema!(schema)).toBeTruthy();
        });
});

describe("Test schemas over samples", () => {
    let ajv: Ajv;

    beforeAll(() => {
        ajv = new Ajv({
            verbose: true,
            allErrors: true,
        });
        getSchemas()
            .map(document => document.content)
            .map(content => parseYaml(content))
            .forEach(sch => ajv.addSchema(sch, sch["$id"]));
    });

    test.each(
        getSamples()
            .map(document => {
                const content = parseYaml(document.content);
                const schema = content["$schema"] ?? getSchema(document.content);
                return [schema, document.path, parseYaml(document.content)];
            })
    )(
        "Test schema %s over sample %s",
        (schema: string, path: string, sample: any) => {
            const result = ajv.validate(schema, sample);
            const expected = !path.endsWith("__SHOULD_FAIL.yaml");
            expect(result, ajv.errorsText() ?? "unknown error").toBe(expected);
        }
    );
});
