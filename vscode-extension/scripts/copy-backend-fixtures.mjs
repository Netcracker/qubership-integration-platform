// Copies the golden post-#553 export trees into the integration workspace, so the extension is
// tested against bytes the backend wrote rather than against fixtures we authored to match it.
//
// The trees live in `schemas`, beside the other two corpora both modules share, but only
// runtime-catalog can produce them. Regenerate with
// `mvn -pl runtime-catalog test -Dtest=GoldenCorpusCapture#capturePost553 -DfailIfNoTests=false`.

import { cpSync, existsSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const extensionRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const goldenRoot = resolve(
  extensionRoot,
  "../schemas/src/test/resources/exportimport-golden",
);

// `post553-dotted` is a second set rather than a fourth service in `post553`: only the service id
// has to be one dot-free segment, so a real export names its api group and api files after dotted
// ids, and no other current-format set carries that shape.
const sets = [
  ["post553", "from-backend"],
  ["post553-dotted", "from-backend-dotted"],
];

for (const [setName, projectName] of sets) {
  const source = resolve(goldenRoot, setName);
  const target = resolve(
    extensionRoot,
    "tests/fixtures/service-projects",
    projectName,
  );

  if (!existsSync(source)) {
    console.error(
      `The golden export set is missing: ${source}\n` +
        "It is checked in under runtime-catalog; run this from a full monorepo checkout.",
    );
    process.exit(1);
  }

  // Remove first: cpSync merges, so a file a mutation renamed would survive beside the restored
  // original, and the workspace would hold one id under two names.
  rmSync(target, { recursive: true, force: true });
  cpSync(source, target, { recursive: true });

  console.log(`Copied ${source} -> ${target}`);
}
