export type FileFilter = {
    extension: string;
    predicate?: (fileContent: any) => boolean;
    findFirst: boolean;
}
