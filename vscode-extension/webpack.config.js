/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

//@ts-check
'use strict';

//@ts-check
/** @typedef {import('webpack').Configuration} WebpackConfig **/

const path = require('path');
const fs = require('fs');
const webpack = require('webpack');
const CopyWebpackPlugin = require('copy-webpack-plugin');

// Locate a workspace package's installed root by walking up the directory tree
// and probing each "node_modules/<name>" candidate. We avoid require.resolve
// here because the target packages declare ESM-only "exports" with no CJS main,
// which makes require.resolve fail under CommonJS even though the packages are
// physically present on disk.
function resolveWorkspacePackageDir(name) {
    let dir = __dirname;
    while (true) {
        const candidate = path.join(dir, 'node_modules', name);
        const pkgJson = path.join(candidate, 'package.json');
        if (fs.existsSync(pkgJson)) {
            return fs.realpathSync(candidate);
        }
        const parent = path.dirname(dir);
        if (parent === dir) {
            throw new Error(`Cannot locate package root for "${name}"`);
        }
        dir = parent;
    }
}

const qipUiDir = resolveWorkspacePackageDir('@netcracker/qip-ui');
const qipSchemasDir = resolveWorkspacePackageDir('@netcracker/qip-schemas');

// Fail fast with an actionable message if upstream workspace artifacts are
// missing — otherwise copy-webpack-plugin emits a low-level "unable to locate"
// error and the user has to dig through the stack to figure out the fix.
const qipUiDistLib = path.join(qipUiDir, 'dist-lib');
const qipUiEntryPoint = path.join(qipUiDistLib, 'index.es.js');
if (!fs.existsSync(qipUiEntryPoint)) {
    throw new Error(
        `qip-ui library bundle is missing (${qipUiEntryPoint} not found).\n` +
        `Run \`npm run build -w @netcracker/qip-vscode-extension\` from the repo root, ` +
        `or \`npm run build\` from vscode-extension/, to build the upstream workspaces first.`
    );
}
const qipSchemasAssets = path.join(qipSchemasDir, 'assets');
if (!fs.existsSync(qipSchemasAssets)) {
    throw new Error(
        `qip-schemas assets directory is missing (${qipSchemasAssets} not found).\n` +
        `Run \`npm run build -w @netcracker/qip-schemas\` to populate it.`
    );
}

/** @type WebpackConfig */
const webExtensionConfig = {
    mode: 'none', // this leaves the source code as close as possible to the original (when packaging we set this to 'production')
    target: 'webworker', // extensions run in a webworker context
    entry: {
        'extension': './src/web/extension.ts',
        'test/suite/index': './src/web/test/suite/index.ts'
    },
    output: {
        filename: '[name].js',
        path: path.join(__dirname, './dist/web'),
        libraryTarget: 'commonjs',
        devtoolModuleFilenameTemplate: '../../[resource-path]'
    },
    resolve: {
        mainFields: ['browser', 'module', 'main'], // look for `browser` entry point in imported node modules
        extensions: ['.ts', '.js'], // support ts-files and js-files
        alias: {
            // provides alternate implementation for node module and source files
        },
        fallback: {
            // Webpack 5 no longer polyfills Node.js core modules automatically.
            // see https://webpack.js.org/configuration/resolve/#resolvefallback
            // for the list of Node.js core module polyfills.
            'assert': require.resolve('assert'),
            'process': require.resolve('process/browser.js')
        }
    },
    module: {
        rules: [{
            test: /\.ts$/,
            exclude: /node_modules/,
            use: [{
                loader: 'ts-loader'
            }]
        }]
    },
    plugins: [
        new webpack.optimize.LimitChunkCountPlugin({
            maxChunks: 1 // disable chunks by default since web extensions must be a single bundle
        }),
        new webpack.ProvidePlugin({
            process: require.resolve('process/browser.js'), // provide a shim for the global `process` variable
        }),
        // Copy webview assets into the extension's own dist/ so they are reachable
        // via context.extensionUri at runtime and are included in the packaged .vsix,
        // independent of where npm workspaces hoist the source packages.
        new CopyWebpackPlugin({
            patterns: [
                // qip-ui runtime bundle + styles. We exclude the types/ tree both
                // because the webview doesn't need .d.ts files and because qip-ui
                // ships casing-conflicting filenames (Filter.d.ts / filter.d.ts)
                // that crash webpack on case-insensitive filesystems. Source maps
                // ARE copied so the webview debugger can map stack traces back to
                // qip-ui source.
                {
                    context: qipUiDistLib,
                    from: '**/*',
                    to: path.join(__dirname, 'dist/web/qip-ui'),
                    globOptions: {
                        ignore: ['**/types/**', '**/*.d.ts'],
                    },
                    noErrorOnMissing: false,
                },
                // qip-schemas: bundled ESM is consumed via webpack import; we still
                // copy assets/ in case the webview loads schema YAMLs by URL.
                {
                    from: qipSchemasAssets,
                    to: path.join(__dirname, 'dist/web/qip-schemas/assets'),
                    noErrorOnMissing: false,
                },
            ],
        }),
    ],
    externals: {
        'vscode': 'commonjs vscode', // ignored because it doesn't exist
    },
    performance: {
        hints: false
    },
    devtool: 'nosources-source-map', // create a source map that points to the original source file
    infrastructureLogging: {
        level: "log", // enables logging required for problem matchers
    },
};

/** @type WebpackConfig */
const nodeLibraryConfig = {
    mode: 'none',
    target: 'node', // build for Node.js environment
    entry: './src/web/index.ts',
    output: {
        filename: 'index.js',
        path: path.join(__dirname, './dist/node'),
        libraryTarget: 'commonjs2'
    },
    resolve: {
        extensions: ['.ts', '.js']
    },
    module: {
        rules: [{
            test: /\.ts$/,
            exclude: /node_modules/,
            use: [{
                loader: 'ts-loader'
            }]
        }]
    },
    externals: {
        'vscode': 'commonjs vscode'
    }
};

module.exports = [webExtensionConfig, nodeLibraryConfig];
