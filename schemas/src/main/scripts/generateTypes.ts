import path from "path";
import fs from "fs";
import os from "os";
import { glob } from "glob";
import yaml from "js-yaml";
import { compile } from "json-schema-to-typescript";
import $RefParser from "@apidevtools/json-schema-ref-parser";

// ===== Config =====
const SCHEMA_SRC_DIR = path.resolve(
  process.cwd(),
  "src/main/resources/qip-model",
);
const TEMP_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "qip-schemas-"));
const GENERATED_DIR = path.resolve(process.cwd(), "types");
const REF_PREFIX = "http://qubership.org/schemas/product/qip/";

// Ensure generated directory exists
fs.mkdirSync(GENERATED_DIR, { recursive: true });

// ===== Helpers =====
function loadSchema(filePath: string) {
  const content = fs.readFileSync(filePath, "utf8");
  if (filePath.endsWith(".yaml") || filePath.endsWith(".yml"))
    return yaml.load(content);
  return JSON.parse(content);
}

function writeSchema(filePath: string, data: any) {
  if (filePath.endsWith(".yaml") || filePath.endsWith(".yml"))
    fs.writeFileSync(filePath, yaml.dump(data), "utf8");
  else fs.writeFileSync(filePath, JSON.stringify(data, null, 2), "utf8");
}

// ===== Rewrite $ref and $id relative to TEMP_DIR =====
function rewriteRefsAndIds(obj: any, currentFile: string): any {
  if (Array.isArray(obj))
    return obj.map((item) => rewriteRefsAndIds(item, currentFile));
  if (obj && typeof obj === "object") {
    const newObj: any = {};
    for (const [key, value] of Object.entries(obj)) {
      if ((key === "$ref" || key === "$id") && typeof value === "string") {
        let newVal = value;
        if (value.startsWith(REF_PREFIX)) {
          const refRelPath = value.substring(REF_PREFIX.length).split("#")[0];
          const fragment = value.includes("#") ? "#" + value.split("#")[1] : "";
          const targetAbsolute = path.resolve(TEMP_DIR, refRelPath);
          newVal = path.join(TEMP_DIR, refRelPath) + fragment;

          if (!fs.existsSync(targetAbsolute)) {
            console.warn(
              `Missing target for ${key}: ${value} → ${targetAbsolute}`,
            );
          }
        }
        newObj[key] = newVal;
      } else {
        newObj[key] = rewriteRefsAndIds(value, currentFile);
      }
    }
    return newObj;
  }
  return obj;
}

// ===== Copy all schema files to temp dir =====
async function copyAllSchemas(): Promise<string[]> {
  const files = await glob("**/*.{yaml,yml,json}", {
    cwd: SCHEMA_SRC_DIR,
    absolute: true,
    nodir: true,
  });
  for (const src of files) {
    const rel = path.relative(SCHEMA_SRC_DIR, src);
    const dest = path.join(TEMP_DIR, rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(src, dest);
  }
  const tempFiles = await glob("**/*.{yaml,yml,json}", {
    cwd: TEMP_DIR,
    absolute: true,
    nodir: true,
  });
  return tempFiles;
}

// ===== Rewrite all refs and ids in temp files =====
async function rewriteAllRefs(tempFiles: string[]) {
  for (const file of tempFiles) {
    const data = loadSchema(file);
    const rewritten = rewriteRefsAndIds(data, file);
    writeSchema(file, rewritten);
  }
}

// ===== Compile schemas to TypeScript =====
async function compileSchemas(tempFiles: string[]) {
  for (const file of tempFiles) {
    try {
      console.log(`Compiling schema: ${path.relative(TEMP_DIR, file)}`);

      // Fully dereference schema locally (no HTTP)
      const schema = await $RefParser.dereference(file, {
        resolve: { file: { order: 1 } },
        dereference: { circular: "ignore" },
      });

      // Compile to TypeScript
      const ts = await compile(schema as any, path.basename(file), {
        cwd: TEMP_DIR,
        bannerComment: "",
      });

      const relPath = path
        .relative(TEMP_DIR, file)
        .replace(/\.(yaml|yml|json)$/, ".d.ts");
      const outFile = path.join(GENERATED_DIR, relPath);
      fs.mkdirSync(path.dirname(outFile), { recursive: true });
      fs.writeFileSync(outFile, ts, "utf8");

      console.log(`Generated types for ${relPath}`);
    } catch (err) {
      console.error(`Failed compiling ${file}`, err);
    }
  }
}

function generateIndexFile() {
  const exports: string[] = [];

  function walk(dir: string, baseDir = GENERATED_DIR) {
    const entries = fs.readdirSync(dir, { withFileTypes: true });

    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);

      if (entry.isDirectory()) {
        walk(fullPath, baseDir);
      } else if (
        entry.isFile() &&
        entry.name.endsWith(".d.ts") &&
        entry.name !== "index.d.ts"
      ) {
        // relative path from GENERATED_DIR
        const relPath =
          "./" + path.relative(baseDir, fullPath).replace(/\\/g, "/");
        // strip `.d.ts` extension
        const importPath = relPath.replace(/\.d\.ts$/, "");
        exports.push(`export * from "${importPath}";`);
      }
    }
  }

  walk(GENERATED_DIR);

  fs.writeFileSync(
    path.join(GENERATED_DIR, "index.d.ts"),
    exports.join("\n") + "\n",
    "utf8",
  );

  console.log(`Generated index.d.ts with ${exports.length} exports.`);
}

export async function generateTypes() {
  try {
    console.log("Temp dir:", TEMP_DIR);
    const tempFiles = await copyAllSchemas();
    await rewriteAllRefs(tempFiles);
    await compileSchemas(tempFiles);
    generateIndexFile();
  } finally {
    // Clean up temp directory
    fs.rmSync(TEMP_DIR, { recursive: true, force: true });
  }
}
