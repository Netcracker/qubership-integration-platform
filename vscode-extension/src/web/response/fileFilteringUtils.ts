import { Uri } from "vscode";

export type FileFilter = {
  extension: string;
  predicate?: (fileContent: any) => boolean;
  findFirst: boolean;
};

/**
 * The scan answered no match, but some file it had to parse could not be read, so the extension
 * cannot be ruled out. Distinct from a plain miss on purpose: a caller that tries one name after
 * another must not read this as "the name holds nothing" and answer from the next one.
 */
export class UnreadableFileError extends Error {
  constructor(
    readonly extension: string,
    readonly files: readonly Uri[],
  ) {
    super(
      `Cannot search *${extension} files: ${files
        .map((fileUri) => fileUri.path)
        .join(", ")} could not be read`,
    );
    this.name = "UnreadableFileError";
  }
}
