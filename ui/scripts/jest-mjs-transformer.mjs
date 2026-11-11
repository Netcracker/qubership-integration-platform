import ts from "typescript";

export default {
  process(source, filePath) {
    return {
      code: ts.transpileModule(source, {
        compilerOptions: {
          module: ts.ModuleKind.CommonJS,
          target: ts.ScriptTarget.ES2021,
        },
        fileName: filePath.replace(/\.mjs$/, ".js"),
      }).outputText,
    };
  },
};
