// What `jest.config.cjs` maps `^vscode$` to. The fake itself lives in `tests/fake-vscode/`, so a
// test file can import its helpers (`seedWorkspace`, `workspaceUri`, `workspaceTree`, `resetWorkspace`)
// from there while reading the API through `vscode`; both resolve to one module instance.
module.exports = require("../fake-vscode");
