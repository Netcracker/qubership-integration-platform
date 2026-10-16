# Welcome to your Visual Studio Code Extension

## What's in the folder

* This folder contains all of the files necessary for your web extension.
* `package.json` * this is the manifest file in which you declare your extension and command.
* `src/web/extension.ts` * this is the main file for the browser
* `webpack.config.js` * the webpack config file for the web main

## Setup

* install the recommended extensions (amodio.tsl-problem-matcher, ms-vscode.extension-test-runner, and dbaeumer.vscode-eslint)

## Get up and running the Web Extension

* Run `npm install`.
* Place breakpoints in `src/web/extension.ts`.
* Debug via F5 (Run Web Extension).
* Execute extension code via `F1 > Hello world`.

## Make changes

* You can relaunch the extension from the debug toolbar after changing code in `src/web/extension.ts`.
* You can also reload (`Ctrl+R` or `Cmd+R` on Mac) the Visual Studio Code window with your extension to load your changes.

## Explore the API

* You can open the full set of our API when you open the file `node_modules/@types/vscode/index.d.ts`.

## Run tests

* Run the integration tests with `npm run test:integration` from this folder. It opens `src/web/test/workspace` in
  VS Code for the Web, which keeps every write in memory, and prints the results to the console.
* Do not run them from the `Extension Tests` launch configuration: it opens no workspace folder, and a desktop host
  would write to the tracked fixtures.
* Add a test as a `*.test.ts` file in `src/web/test/suite`, written with `suite` and `test`.
* `npm run test:unit` runs the Jest suite under `tests/` and beside `src/web/api-services/`.

## Go further

* [Follow UX guidelines](https://code.visualstudio.com/api/ux-guidelines/overview) to create extensions that seamlessly integrate with VS Code's native interface and patterns.
* Check out the [Web Extension Guide](https://code.visualstudio.com/api/extension-guides/web-extensions).
* [Publish your extension](https://code.visualstudio.com/api/working-with-extensions/publishing-extension) on the Visual Studio Code extension marketplace.
* Automate builds by setting up [Continuous Integration](https://code.visualstudio.com/api/working-with-extensions/continuous-integration).
