import { spawnSync } from "node:child_process";
import fs from "node:fs";
import { setTimeout } from "node:timers/promises";

const [sample, file, interval, lifetime] = process.argv.slice(2);
const { command, args } = JSON.parse(sample);
const deadline = Date.now() + Number(lifetime);
const output = fs.openSync(file, "a");
try {
  while (Date.now() < deadline) {
    spawnSync(command, args, {
      stdio: ["ignore", output, "ignore"],
      windowsHide: true,
      timeout: Math.max(1, deadline - Date.now()),
    });
    await setTimeout(Math.max(0, Math.min(Number(interval), deadline - Date.now())));
  }
} finally {
  fs.closeSync(output);
}
