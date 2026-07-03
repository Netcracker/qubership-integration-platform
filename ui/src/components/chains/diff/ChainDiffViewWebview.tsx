import React, { useEffect, useState } from "react";
import {
  COMPARED_DOCUMENTS_REQUEST_EVENT,
  VSCodeExtensionApi,
  VSCodeResponse,
} from "../../../api/rest/vscodeExtensionApi.ts";
import { api } from "../../../api/api.ts";
import { ChainDiffView } from "./ChainDiffView.tsx";
import { ComparableItem } from "./useChainDiff.tsx";
import { Chain } from "../../../api/apiTypes.ts";

export const ChainDiffViewWebview: React.FC = (): React.ReactNode => {
  const [item1, setItem1] = useState<ComparableItem | undefined>(undefined);
  const [item2, setItem2] = useState<ComparableItem | undefined>(undefined);

  useEffect(() => {
    const onMessage = (
      event: MessageEvent<VSCodeResponse<{ original: Chain; modified: Chain }>>,
    ) => {
      if (event.data.type === "comparedDocumentsResponse") {
        setItem1({ kind: "document", content: event.data.payload!.original });
        setItem2({ kind: "document", content: event.data.payload!.modified });
      }
    };

    window.addEventListener("message", onMessage);
    return () => {
      window.removeEventListener("message", onMessage);
    };
  }, []);

  useEffect(() => {
    if (api instanceof VSCodeExtensionApi) {
      void api.sendMessageToExtension(COMPARED_DOCUMENTS_REQUEST_EVENT);
    }
  }, []);

  return item1 && item2 ? (
    <ChainDiffView
      style={{ padding: "16px" }}
      item1={item1}
      item2={item2}
      editable1={false}
      editable2={false}
    />
  ) : null;
};
