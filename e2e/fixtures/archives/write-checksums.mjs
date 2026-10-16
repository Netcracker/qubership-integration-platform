/**
 * Rewrites `frozen/CHECKSUMS` from the tree.
 *
 * Run it **after** deliberately changing a frozen document, in the same commit, so the change and
 * its digest reach a reviewer together. Running it to make a red `frozen-corpus.spec.ts` go green
 * is the failure the guard exists to catch.
 *
 *     npm run frozen-checksums
 */
import fs from "node:fs";
import { CHECKSUM_FILE, renderChecksums } from "./frozen.ts";

fs.writeFileSync(CHECKSUM_FILE, renderChecksums());
console.log(`wrote ${CHECKSUM_FILE}`);
