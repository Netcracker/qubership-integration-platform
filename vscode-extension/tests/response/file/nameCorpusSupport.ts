import * as fs from "fs";
import * as path from "path";
import * as yaml from "js-yaml";

/**
 * The service file naming corpus in `schemas/src/test/resources/naming`, read by relative path the
 * way the conformance corpus is. The corpus states the rule for both implementations of it, so
 * neither authors it — see the README beside it before changing an outcome recorded there.
 */
export type NameCorpus = {
  rule: {
    current: { postfixes: Record<string, string> };
    // Written only by the #553 versions. Both sides read these names; neither writes one.
    perType: { postfixes: Record<string, string> };
    // Java-only: nothing here discovers a flat name.
    flat: Record<string, { discoverable: boolean }>;
  };
  types: {
    schemaFileStems: Record<string, string>;
    defaultSchemaUris: Record<string, string>;
    // Per-side outcomes: `no-type`, or the kind the side resolves. A disagreement between the two
    // sides carries a written `divergence`, same as a `classify` entry.
    statingNoPlainType: {
      uri: string;
      java: string;
      ts: string;
      reason?: string;
      divergence?: string;
    }[];
    // The extension alone stems the two typeless kinds; Java deliberately does not.
    extensionOnlySchemaFileStems: Record<string, string>;
  };
  alphabet: {
    serviceIds: string[];
    appNames: string[];
    kinds: string[];
    plainKinds: string[];
  };
  classify: {
    name: string;
    fileName: string;
    // The archive folder the file sits in. Java-only context — its discovery reads the postfix
    // after the directory name too — and ignored here, where the whole extension is compared.
    directory: string;
    appName: string;
    java: string;
    ts: string;
    reason?: string;
    divergence?: string;
  }[];
};

export function loadNameCorpus(): NameCorpus {
  return yaml.load(
    fs.readFileSync(
      path.resolve(
        __dirname,
        "../../../../schemas/src/test/resources/naming/service-file-names.yaml",
      ),
      "utf8",
    ),
  ) as NameCorpus;
}

/** The name the declared rule builds, so no test spells a file name itself. */
export function currentFormatName(
  corpus: NameCorpus,
  id: string,
  kind: string,
  appName: string,
): string {
  return `${id}${corpus.rule.current.postfixes[kind]}${appName}.yaml`;
}

/** A per-type name — a format both implementations read and neither writes. */
export function perTypeName(
  corpus: NameCorpus,
  id: string,
  kind: string,
  appName: string,
): string {
  return `${id}${corpus.rule.perType.postfixes[kind]}${appName}.yaml`;
}
