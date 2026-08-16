import React from "react";
import { TestingMatcherParameter } from "../../../api/apiTypes.ts";
import { InlineEdit } from "../../InlineEdit.tsx";
import { SelectEdit } from "../../table/SelectEdit.tsx";
import { MatcherParametersView } from "./MatcherParametersView.tsx";
import { HTTP_STATUS_CODE_OPTIONS } from "./httpStatusCodes.ts";

export type HttpStatusCodeEditorProps = {
  parameters: TestingMatcherParameter[] | null;
  onChange: (parameters: TestingMatcherParameter[]) => void;
};

/** Picks the status an `equal` matcher over the response status compares against. */
export const HttpStatusCodeEditor: React.FC<HttpStatusCodeEditorProps> = ({
  parameters,
  onChange,
}) => {
  const value =
    parameters?.find((parameter) => parameter.name === "value")?.value ?? "";

  return (
    <InlineEdit<{ statusCode: string }>
      values={{ statusCode: value }}
      editor={
        <SelectEdit<string>
          name="statusCode"
          options={HTTP_STATUS_CODE_OPTIONS}
          selectProps={{ showSearch: true, "aria-label": "Status code" }}
          shouldSubmitOnChange={() => true}
        />
      }
      viewer={<MatcherParametersView parameters={parameters} />}
      onSubmit={({ statusCode }) =>
        onChange(statusCode ? [{ name: "value", value: statusCode }] : [])
      }
    />
  );
};
