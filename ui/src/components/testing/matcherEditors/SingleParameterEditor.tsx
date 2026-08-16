import React from "react";
import { TestingMatcherParameter } from "../../../api/apiTypes.ts";
import { InlineEdit } from "../../InlineEdit.tsx";
import { TextValueEdit } from "../../table/TextValueEdit.tsx";
import { MatcherParametersView } from "./MatcherParametersView.tsx";

export type SingleParameterEditorProps = {
  /** The name the matching engine reads — `value` for most types, `pattern` for `match`. */
  parameterName: string;
  parameters: TestingMatcherParameter[] | null;
  onChange: (parameters: TestingMatcherParameter[]) => void;
};

export const SingleParameterEditor: React.FC<SingleParameterEditorProps> = ({
  parameterName,
  parameters,
  onChange,
}) => {
  const value =
    parameters?.find((parameter) => parameter.name === parameterName)?.value ??
    "";

  return (
    <InlineEdit<{ parameterValue: string }>
      values={{ parameterValue: value }}
      editor={
        <TextValueEdit
          name="parameterValue"
          inputProps={{ "aria-label": parameterName }}
          rules={[{ required: true, message: `${parameterName} is required.` }]}
        />
      }
      viewer={<MatcherParametersView parameters={parameters} />}
      onSubmit={({ parameterValue }) =>
        onChange([{ name: parameterName, value: parameterValue }])
      }
    />
  );
};
