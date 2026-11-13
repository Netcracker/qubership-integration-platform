import { test } from "../../support/fixtures.js";
import { saveProcessSnapshot } from "../../env/process-snapshot.js";

test("records process state before intentional restarts", { tag: ["@infra"] }, async ({ env, run }) => {
  await saveProcessSnapshot(env, run);
});
