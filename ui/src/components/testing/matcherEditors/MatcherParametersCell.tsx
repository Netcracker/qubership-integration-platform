import React from "react";
import { theme, Tooltip, Typography } from "antd";
import {
  TestingMatcher,
  TestingMatcherParameter,
} from "../../../api/apiTypes.ts";
import {
  getMatcherParameterEditor,
  validateMatcherParameters,
} from "../matchers.ts";
import { HttpStatusCodeEditor } from "./HttpStatusCodeEditor.tsx";
import { JsonParametersEditor } from "./JsonParametersEditor.tsx";
import { MatcherParametersView } from "./MatcherParametersView.tsx";
import { SingleParameterEditor } from "./SingleParameterEditor.tsx";

export type MatcherParametersCellProps = {
  matcher: TestingMatcher;
  readonly?: boolean;
  onChange: (parameters: TestingMatcherParameter[]) => void;
};

/** Selects the editor the matcher type calls for, and flags invalid parameters. */
export const MatcherParametersCell: React.FC<MatcherParametersCellProps> = ({
  matcher,
  readonly,
  onChange,
}) => {
  const { token } = theme.useToken();
  const editor = getMatcherParameterEditor(matcher.type, matcher.entityType);
  const errors = validateMatcherParameters(matcher.type, matcher.parameters);

  const content = () => {
    if (editor.kind === "none") {
      return <Typography.Text type="secondary">Not applicable</Typography.Text>;
    }
    if (readonly) {
      return <MatcherParametersView parameters={matcher.parameters} />;
    }
    if (editor.kind === "json") {
      return (
        <JsonParametersEditor
          documentParameterName={editor.documentParameterName}
          parameters={matcher.parameters}
          onChange={onChange}
        />
      );
    }
    if (editor.kind === "status") {
      return (
        <HttpStatusCodeEditor
          parameters={matcher.parameters}
          onChange={onChange}
        />
      );
    }
    return (
      <SingleParameterEditor
        parameterName={editor.parameterName}
        parameters={matcher.parameters}
        onChange={onChange}
      />
    );
  };

  if (errors.length === 0) {
    return content();
  }
  return (
    <Tooltip title={errors.join(" ")}>
      <span
        data-testid="matcher-parameters-invalid"
        style={{
          display: "block",
          borderBottom: `1px solid ${token.colorError}`,
        }}
      >
        {content()}
      </span>
    </Tooltip>
  );
};
