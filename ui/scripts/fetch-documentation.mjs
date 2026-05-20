import fs from "fs";
import path from "path";
import { execSync } from "child_process";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function loadDocumentationConfig() {
  const configPath = path.resolve(process.cwd(), ".documentation-config.json");

  if (!fs.existsSync(configPath)) {
    console.warn("[Documentation] Config file not found:", configPath);
    console.warn(
      "[Documentation] Create .documentation-config.json or copy from .documentation-config.json.example",
    );
    return null;
  }

  try {
    const content = fs.readFileSync(configPath, "utf-8");
    const parsed = JSON.parse(content);

    if (!parsed.documentation) {
      console.error(
        '[Documentation] Config file missing "documentation" field',
      );
      return null;
    }

    return parsed.documentation;
  } catch (error) {
    console.error("[Documentation] Failed to load config:", error);
    return null;
  }
}

function copySourceToDest(sourceDir, dest) {
  if (fs.existsSync(dest)) {
    fs.rmSync(dest, { recursive: true, force: true });
  }
  fs.mkdirSync(dest, { recursive: true });
  fs.cpSync(sourceDir, dest, { recursive: true });
  console.log(`[Documentation] Copied to ${dest}`);
}

function fetchFromNpm(config, dest) {
  const { package: pkg, version, path: sourcePath = "docs" } = config;

  if (!pkg) {
    throw new Error('npm source requires "package" field');
  }

  const pkgWithVersion = version ? `${pkg}@${version}` : pkg;
  const tempDir = path.join(process.cwd(), ".temp-npm-docs");

  try {
    console.log(`[Documentation] Installing npm package: ${pkgWithVersion}`);

    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }

    execSync(`npm install ${pkgWithVersion}`, {
      cwd: tempDir,
      stdio: "inherit",
    });

    const packageDir = path.join(tempDir, "node_modules", pkg);
    const sourceDir = path.join(packageDir, sourcePath);

    if (!fs.existsSync(sourceDir)) {
      throw new Error(`Source path not found in package: ${sourceDir}`);
    }

    copySourceToDest(sourceDir, dest);
  } finally {
    if (fs.existsSync(tempDir)) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  }
}

function fetchFromGit(config, dest) {
  const { repository, branch = "master", path: sourcePath = "docs" } = config;

  if (!repository) {
    throw new Error('git source requires "repository" field');
  }

  const tempDir = path.join(process.cwd(), ".temp-git-docs");

  try {
    console.log(
      `[Documentation] Cloning from git: ${repository} (branch: ${branch})`,
    );

    if (fs.existsSync(tempDir)) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }

    execSync(`git clone -b ${branch} --depth 1 ${repository} ${tempDir}`, {
      stdio: "inherit",
    });

    const sourceDir = path.join(tempDir, sourcePath);
    if (!fs.existsSync(sourceDir)) {
      throw new Error(`Source path not found in repository: ${sourceDir}`);
    }

    copySourceToDest(sourceDir, dest);
  } finally {
    if (fs.existsSync(tempDir)) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  }
}

function fetchFromLocal(config, dest) {
  const { path: sourcePath } = config;

  if (!sourcePath) {
    throw new Error('local source requires "path" field');
  }

  const sourceDir = path.resolve(process.cwd(), sourcePath);

  if (!fs.existsSync(sourceDir)) {
    throw new Error(`Source directory not found: ${sourceDir}`);
  }

  console.log(`[Documentation] Copying from local: ${sourceDir}`);
  copySourceToDest(sourceDir, dest);
}

function fetchDocumentation(config, dest) {
  switch (config.source) {
    case "npm":
      fetchFromNpm(config, dest);
      break;
    case "git":
      fetchFromGit(config, dest);
      break;
    case "local":
      fetchFromLocal(config, dest);
      break;
    default:
      throw new Error(`Unknown source type: ${config.source}`);
  }
}

function generateIndexes(dest) {
  const indexPath = path.join(__dirname, "build-doc-index.mjs");

  if (!fs.existsSync(indexPath)) {
    console.warn(
      "[Documentation] build-doc-index.mjs not found, skipping index generation",
    );
    return;
  }

  if (!fs.existsSync(dest)) {
    console.warn(
      "[Documentation] Destination directory not found, skipping index generation",
    );
    return;
  }

  console.log("[Documentation] Generating indexes...");
  try {
    execSync(`node ${indexPath} ${dest} ${dest}`, {
      stdio: "inherit",
    });
    console.log("[Documentation] Indexes generated successfully");
  } catch (error) {
    console.error("[Documentation] Failed to generate indexes:", error);
    throw error;
  }
}

function main() {
  console.log("[Documentation] Starting documentation fetch...");

  const config = loadDocumentationConfig();
  if (!config) {
    console.warn("[Documentation] Skipping documentation fetch");
    return;
  }

  const dest = config.destination || "public/doc";

  try {
    console.log(`[Documentation] Fetching from source: ${config.source}`);
    fetchDocumentation(config, dest);

    console.log("[Documentation] Generating indexes...");
    generateIndexes(dest);

    console.log("[Documentation] Done!");
  } catch (error) {
    console.error("[Documentation] Error:", error);
    process.exit(1);
  }
}

main();
