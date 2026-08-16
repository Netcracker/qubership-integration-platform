import React from "react";
import { Button } from "antd";
import { TestingMatcherParameter } from "../../../api/apiTypes.ts";
import { useModalsContext } from "../../../Modals.tsx";
import { JsonMatcherParametersModal } from "../../modal/testing/JsonMatcherParametersModal.tsx";
import { MatcherParametersView } from "./MatcherParametersView.tsx";

export type JsonParametersEditorProps = {
  /** `schema` for a JSON Schema matcher, `sample` for a JSON one. */
  documentParameterName: string;
  parameters: TestingMatcherParameter[] | null;
  onChange: (parameters: TestingMatcherParameter[]) => void;
};

/** The path and the JSON document need more room than a cell, so they get a modal. */
export const JsonParametersEditor: React.FC<JsonParametersEditorProps> = ({
  documentParameterName,
  parameters,
  onChange,
}) => {
  const { showModal } = useModalsContext();

  return (
    <Button
      type="link"
      size="small"
      style={{ padding: 0, textAlign: "left", height: "auto" }}
      aria-label={`Edit ${documentParameterName}`}
      onClick={() =>
        showModal({
          component: (
            <JsonMatcherParametersModal
              documentParameterName={documentParameterName}
              parameters={parameters}
              onSubmit={onChange}
            />
          ),
        })
      }
    >
      <MatcherParametersView parameters={parameters} />
    </Button>
  );
};
