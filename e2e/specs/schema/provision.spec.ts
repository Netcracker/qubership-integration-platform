/**
 * The paths provisioning watches, read against the compose file and the checkout.
 *
 * A watched path that has moved is skipped without an error, so the service it belongs to reads as
 * current forever. These cases fail when a mount, a module, or a Dockerfile moves without the table
 * in `env/provision/compose.ts` moving with it. `specs/harness/provision.spec.ts` covers the rest of
 * provisioning.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { composeFile } from "../../env/compose.js";
import { repoRoot } from "../../env/host.js";
import { provisionedSources, supportConfig } from "../../env/provision/compose.js";

test.describe("provisioning watches against the checkout", { tag: ["@schema"] }, () => {
  test("each support service watches its compose bind mounts", () => {
    // A path that has moved is not an error: `newestMtimeMs` skips one that does not exist, so the
    // service reads as current forever and the run says "nothing to do" over a container holding a
    // consul policy or an init script from before the change. Nothing else would catch that, which is
    // why the list is read back against the mounts it was written from.
    const compose = fs.readFileSync(composeFile(), "utf-8");
    const watched = supportConfig().flatMap((each) => each.config);
    expect(watched.length, "no support service watches any bind mount").toBeGreaterThan(0);

    for (const each of watched) {
      expect(fs.existsSync(path.resolve(repoRoot(), each)), `${each} is watched but not in the checkout`).toBe(true);
      // The compose file spells a mount relative to its own directory, so `infrastructure/docker/consul`
      // reads as `./consul`. A watch may be broader than the mount and consul's is: the compose file
      // mounts `./consul/server.json` and `./consul/consul-acl.json` by name, and watching the
      // directory is what makes a third file added beside them count.
      const mount = `./${each.replace(/^infrastructure\/docker\//, "")}`;
      const mounted = new RegExp(`${mount.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}[:/]`);
      expect(mounted.test(compose), `${each} is watched but ${mount} is mounted nowhere`).toBe(true);
    }

    // Every path listed being real is half of it. The other half is that the three containers holding
    // this stack's data still list one: an emptied entry reads as a service with nothing to watch, and
    // the notice then stays silent over a postgres that never ran the init script it was given.
    const empty = supportConfig().filter((each) => each.config.length === 0).map((each) => each.service);
    // The proxy is the deliberate exception: its `./nginx` is answered by a reload rather than by a
    // recreate, so listing it here would recreate a container the suite has a cheaper answer for.
    expect(empty, "a support service watches nothing bind-mounted into it").toEqual(["ui-proxy"]);
  });

  test("every source a service is rebuilt for is in the checkout", () => {
    for (const each of provisionedSources()) {
      expect(each.sources.length, `${each.service} watches nothing`).toBeGreaterThan(0);
      for (const source of each.sources) {
        expect(
          fs.existsSync(path.resolve(repoRoot(), source)),
          `${each.service} watches ${source}, which is not in the checkout`,
        ).toBe(true);
      }

      // Two of the three inputs to a jar this image runs sit outside the module. `parent/pom.xml` is
      // the parent of every Spring service and carries the dependency versions and the repackage
      // plugin, so a bump there changes every jar the build produces; the `Dockerfile` is the only
      // other input to the image, since all three copy nothing but `target/`. Without them a run after
      // either edit prints "nothing to do" and tests the jars from before it. The Go service has no
      // Maven module and watches its whole directory, Dockerfile included.
      if (each.mavenModule === undefined) continue;
      expect(each.sources, `${each.service} is not rebuilt for a change to the parent POM`).toContain(
        "parent/pom.xml",
      );
      expect(each.sources, `${each.service} is not rebuilt for a change to its Dockerfile`).toContain(
        `${each.mavenModule}/Dockerfile`,
      );
    }
  });
});
