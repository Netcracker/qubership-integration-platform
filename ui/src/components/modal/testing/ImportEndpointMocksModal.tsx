import React from "react";
import { api } from "../../../api/api.ts";
import { TestingImportModal } from "./TestingImportModal.tsx";

export type ImportEndpointMocksModalProps = {
  onImported: () => void;
};

export const ImportEndpointMocksModal: React.FC<
  ImportEndpointMocksModalProps
> = ({ onImported }) => (
  <TestingImportModal
    title="Import Endpoint Mocks"
    failureMessage="Failed to import endpoint mocks"
    importFiles={(files) => api.importEndpointMocks(files)}
    onImported={onImported}
  />
);
