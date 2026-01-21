#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const indexFile = path.join(__dirname, '..', 'dist-lib', 'index.es.js');

// Check if file exists
if (!fs.existsSync(indexFile)) {
  console.log('Index file not found, skipping preload fix');
  process.exit(0);
}

try {
  let content = fs.readFileSync(indexFile, 'utf8');

  // Count occurrences of __vitePreload
  const preloadCount = (content.match(/__vitePreload/g) || []).length;

  if (preloadCount <= 1) {
    console.log('No duplicate __vitePreload found, skipping fix');
    process.exit(0);
  }

  console.log(`Found ${preloadCount} occurrences of __vitePreload, fixing...`);

  // Replace all occurrences with unique names
  let counter = 0;
  content = content.replace(/__vitePreload/g, () => `__vitePreload_${counter++}`);

  fs.writeFileSync(indexFile, content, 'utf8');

  console.log('Successfully fixed __vitePreload conflicts in built library');

} catch (error) {
  console.error('Error fixing __vitePreload:', error.message);
  process.exit(1);
}