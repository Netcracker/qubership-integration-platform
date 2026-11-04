/** What both provisioners share: the empty report they fill, and the Maven install before an image build. */
import { repoRoot, stream } from "../host.js";
import type { ProvisionMode, ProvisionReport } from "../provision.js";

export function emptyProvisionReport(mode: ProvisionMode): ProvisionReport {
  return {
    mode,
    durationMs: 0,
    built: [],
    rebuilt: [],
    recreated: [],
    started: [],
    untouched: [],
    published: [],
    staleSupport: [],
    proxyReloaded: false,
    proxyConfigChanged: false,
  };
}

/** The `mvn` arguments that install `modules` and what they depend on, without tests, signing, javadoc, or source jars. */
export function mavenArguments(modules: readonly string[], args: readonly string[] = []): string[] {
  return [
    "-pl",
    modules.join(","),
    "-am",
    "clean",
    "install",
    "-DskipTests",
    "-Dgpg.skip=true",
    "-Dmaven.javadoc.skip=true",
    "-Dmaven.source.skip=true",
    ...args,
  ];
}

/** Installs `modules` and what they depend on, with the output on the terminal. */
export async function mavenInstall(modules: readonly string[], args: readonly string[] = []): Promise<void> {
  console.log(`[provision] mvn install: ${modules.join(", ")}${args.length ? ` ${args.join(" ")}` : ""}`);
  await stream("mvn", mavenArguments(modules, args), repoRoot());
}
