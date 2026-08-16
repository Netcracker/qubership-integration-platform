import React from "react";
import { api } from "../../../api/api.ts";
import { TestingImportModal } from "./TestingImportModal.tsx";

export type ImportTestCasesModalProps = {
  onImported: () => void;
};

export const ImportTestCasesModal: React.FC<ImportTestCasesModalProps> = ({
  onImported,
}) => (
  <TestingImportModal
    title="Import Test Cases"
    failureMessage="Failed to import test cases"
    importFiles={(files) => api.importTestCases(files)}
    onImported={onImported}
  />
);
