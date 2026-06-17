/** Public command payload — shape-compatible with PD/LCP integration, no LCP import. */
export type ExportImagesCommandConfig = {
  outputDir: string;
  filePaths?: string[];
};
