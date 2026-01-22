#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const distLibDir = path.join(__dirname, '..', 'dist-lib');
const externalFile = path.join(distLibDir, 'index.es.js');
const bundledFile = path.join(distLibDir, 'index.bundled.es.js');

function fixPreloadInFile(filePath, fileName) {
  if (!fs.existsSync(filePath)) {
    console.log(`${fileName} not found, skipping preload fix`);
    return;
  }

  try {
    let content = fs.readFileSync(filePath, 'utf8');

    // Count occurrences of __vitePreload
    const preloadCount = (content.match(/__vitePreload/g) || []).length;

    if (preloadCount <= 1) {
      console.log(`No duplicate __vitePreload found in ${fileName}, skipping fix`);
      return;
    }

    console.log(`Found ${preloadCount} occurrences of __vitePreload in ${fileName}, fixing...`);

    // Replace all occurrences with a unique name to avoid conflicts when used as a library
    content = content.replace(/__vitePreload/g, '__qipVitePreload');

    fs.writeFileSync(filePath, content, 'utf8');

    console.log(`Successfully fixed __vitePreload conflicts in ${fileName}`);

  } catch (error) {
    console.error(`Error fixing __vitePreload in ${fileName}:`, error.message);
    process.exit(1);
  }
}

// Fix both external and bundled versions
fixPreloadInFile(externalFile, 'index.es.js');
fixPreloadInFile(bundledFile, 'index.bundled.es.js');
