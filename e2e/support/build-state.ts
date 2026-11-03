import fs from "node:fs";
import path from "node:path";
import { imageTag, newestMtimeMs } from "../env/host.js";

export class BuildState {
  constructor(
    readonly dir: string,
    readonly inputs: readonly string[],
    readonly marker: string,
    readonly outputs: readonly string[] = [],
  ) {}

  fingerprint(): string {
    return imageTag(this.dir, this.inputs);
  }

  async isStale(fingerprint = this.fingerprint()): Promise<boolean> {
    for (const output of this.outputs) {
      if ((await newestMtimeMs(this.dir, [output])) === 0) return true;
    }
    try {
      return fs.readFileSync(path.resolve(this.dir, this.marker), "utf-8") !== fingerprint;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return true;
      throw error;
    }
  }

  record(fingerprint: string): void {
    fs.writeFileSync(path.resolve(this.dir, this.marker), fingerprint);
  }

  async build(body: () => Promise<void>): Promise<void> {
    const fingerprint = this.fingerprint();
    fs.rmSync(path.resolve(this.dir, this.marker), { force: true });
    await body();
    this.record(fingerprint);
  }
}
