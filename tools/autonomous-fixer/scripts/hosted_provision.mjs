import path from "node:path";
import { registerHooks } from "node:module";
import { pathToFileURL } from "node:url";

const [action, workspace] = process.argv.slice(2);
if (!workspace || !path.isAbsolute(workspace)) throw new Error("An absolute checkout path is required");

// The Playwright sources refer to TypeScript modules with their emitted JavaScript paths.
registerHooks({
  resolve(specifier, context, nextResolve) {
    try {
      return nextResolve(specifier, context);
    } catch (error) {
      if (error.code === "ERR_MODULE_NOT_FOUND" && specifier.startsWith(".") && specifier.endsWith(".js")) {
        return nextResolve(specifier.slice(0, -3) + ".ts", context);
      }
      throw error;
    }
  },
});

const fromCheckout = (file) => import(pathToFileURL(path.join(workspace, file)).href);

switch (action) {
  case "stand": {
    const { provisionK8s } = await fromCheckout("e2e/env/provision/k8s.ts");
    console.log(JSON.stringify(await provisionK8s("auto", []), null, 2));
    break;
  }
  case "preview": {
    const { ensureSchemas } = await fromCheckout("e2e/support/schemas-build.ts");
    const { ensureUiServer } = await fromCheckout("e2e/support/ui-server.ts");
    await ensureSchemas();
    console.log(JSON.stringify(await ensureUiServer()));
    break;
  }
  case "release": {
    const { releaseUiServer } = await fromCheckout("e2e/support/ui-server.ts");
    await releaseUiServer(Number(process.env.E2E_UI_PREVIEW_OWNER_PID));
    break;
  }
  default:
    throw new Error("Expected stand, preview, or release");
}
