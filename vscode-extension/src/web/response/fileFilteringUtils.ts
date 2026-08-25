export type FileFilter = {
  extension: string;
  predicate?: (fileContent: any) => boolean;
  findFirst: boolean;
};

// The scan's third outcome is declared with the rest of the contract, next to the base class every
// rethrow checks against. Re-exported here, where the scan that raises it lives.
export { UnreadableFileError } from "./file/lookupOutcome";
