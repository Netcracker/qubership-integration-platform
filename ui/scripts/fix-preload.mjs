import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distLibDir = path.join(__dirname, "..", "dist-lib");

function fixPreloadInFile(filePath, fileName) {
  if (!fs.existsSync(filePath)) {
    console.log(`${fileName} not found, skipping preload fix`);
    return;
  }

  try {
    let content = fs.readFileSync(filePath, "utf8");

    const preloadCount = (content.match(/__vitePreload/g) || []).length;

    if (preloadCount <= 1) {
      console.log(
        `No duplicate __vitePreload found in ${fileName}, skipping fix`,
      );
      return;
    }

    console.log(
      `Found ${preloadCount} occurrences of __vitePreload in ${fileName}, fixing...`,
    );

    content = content.replace(/__vitePreload/g, "__qipVitePreload");

    fs.writeFileSync(filePath, content, "utf8");

    console.log(`Successfully fixed __vitePreload conflicts in ${fileName}`);
  } catch (error) {
    console.error(`Error fixing __vitePreload in ${fileName}:`, error.message);
    process.exit(1);
  }
}

fixPreloadInFile(path.join(distLibDir, "index.es.js"), "index.es.js");
fixPreloadInFile(path.join(distLibDir, "index.bundled.es.js"), "index.bundled.es.js");
