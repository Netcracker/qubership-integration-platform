import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { setTimeout } from "node:timers/promises";
import { test } from "node:test";
import { preparationJob } from "./prepare-docker-desktop-ambient.mjs";

const IMAGE = "alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f";
const DAEMON = "docker:28-dind@sha256:2a232a42256f70d78e3cc5d2b5d6b3276710a0de0596c145f627ecfae90282ac";

/** @param {string[]} args @param {boolean} [check] */
function docker(args, check = true) {
  const result = spawnSync("docker", args, { encoding: "utf8", timeout: 180_000 });
  if (result.error) throw result.error;
  if (check && result.status !== 0) throw new Error(result.stderr);
  return result;
}

test("ambient netns propagation preserves existing namespaces", { timeout: 240_000 }, async () => {
  const node = `cip-e2e-netns-${process.pid}`;
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "cip-netns-"));
  const script = fs.readFileSync(new URL("./prepare-ambient-netns.sh", import.meta.url), "utf8");
  const job = preparationJob("--prepare", script);
  const command = job.spec.template.spec.containers[0].command;
  /** @param {string[]} args */
  const inside = (args) => docker(["exec", node, ...args]).stdout.trim();
  /** @param {string} action */
  const prepare = (action) => docker([
    "exec", node, "env", `NODE_ACTION=${action}`, `NODE_SCRIPT=${script}`, ...command,
  ], false);
  const mountPoints = () => inside(["cat", "/proc/1/mountinfo"]).split("\n").filter((line) => {
    const target = line.split(" ")[4];
    return target === "/" || target === "/run";
  });
  try {
    docker(["pull", IMAGE]);
    docker(["run", "-d", "--rm", "--name", node, "--privileged", "--network", "none",
      "--memory", "512m", "--cpus", "1", "--pids-limit", "512",
      "--tmpfs", "/var/lib/docker:rw,exec,size=256m", "-e", "DOCKER_TLS_CERTDIR=",
      "--entrypoint", "dockerd", DAEMON, "--host", "unix:///var/run/docker.sock",
      "--bridge", "none", "--iptables=false", "--ip6tables=false", "--storage-driver", "vfs"]);
    const deadline = Date.now() + 30_000;
    while (docker(["exec", node, "docker", "info"], false).status !== 0) {
      assert.ok(Date.now() < deadline, "the isolated Docker daemon did not start");
      await setTimeout(250);
    }
    const archive = path.join(temporary, "alpine.tar");
    docker(["image", "save", "-o", archive, IMAGE]);
    const loaded = spawnSync("docker", ["exec", "-i", node, "docker", "load"], { input: fs.readFileSync(archive) });
    assert.equal(loaded.status, 0, String(loaded.stderr));
    const imageId = inside(["docker", "image", "ls", "--no-trunc", "--format", "{{.ID}}"]);
    assert.match(imageId, /^sha256:[a-f0-9]{64}$/);
    inside(["sh", "-eu", "-c", "mkdir -p /run/netns; touch /run/netns/existing; mount --bind /proc/self/ns/net /run/netns/existing"]);
    const inode = inside(["stat", "-Lc", "%i", "/run/netns/existing"]);
    const before = mountPoints();
    const mount = ["--mount", "type=bind,src=/run/netns,dst=/host/var/run/netns,bind-propagation=rslave"];
    const blocked = docker(["exec", node, "docker", "run", "--rm", "--network", "none", ...mount, imageId, "/bin/true"], false);
    assert.notEqual(blocked.status, 0);
    assert.match(blocked.stderr, /not a shared or slave mount/);
    assert.equal(prepare("--check").status, 2);
    const applied = prepare("--prepare");
    assert.equal(applied.status, 0, applied.stderr);
    assert.match(applied.stdout, /propagation=shared/);
    assert.deepEqual(mountPoints(), before, "the parent mounts must remain unchanged");
    inside(["docker", "run", "-d", "--name", "observer", "--network", "none", ...mount, imageId, "/bin/sleep", "600"]);
    assert.equal(inside(["docker", "exec", "observer", "stat", "-Lc", "%i", "/host/var/run/netns/existing"]), inode);
    inside(["sh", "-eu", "-c", "touch /run/netns/later; mount --bind /proc/self/ns/net /run/netns/later"]);
    assert.equal(inside(["docker", "exec", "observer", "stat", "-Lc", "%i", "/host/var/run/netns/later"]), inode);
    const count = () => inside(["cat", "/proc/1/mountinfo"]).split("\n").filter((line) => line.split(" ")[4] === "/run/netns").length;
    const mounts = count();
    assert.equal(prepare("--prepare").status, 0);
    assert.equal(prepare("--check").status, 0);
    assert.equal(count(), mounts, "preparation must not add another bind mount");
  } finally {
    docker(["rm", "-f", node], false);
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
