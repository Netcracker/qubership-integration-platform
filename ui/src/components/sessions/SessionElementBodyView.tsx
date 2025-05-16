import React, { useEffect, useState } from "react";
import { Flex } from "antd";
import { Editor } from "@monaco-editor/react";

type SessionElementBodyViewProps = React.HTMLAttributes<HTMLElement> & {
  title: string;
  headers: Record<string, string>;
  body?: string;
};

function guessLanguageFromContentType(
  contentType: string | undefined,
): string | undefined {
  if (contentType?.includes("json")) {
    return "json";
  } else if (contentType?.includes("xml")) {
    return "xml";
  } else if (contentType?.includes("yaml") || contentType?.includes("yml")) {
    return "yaml";
  }
  return contentType;
}

function getContentType(headers: Record<string, string>): string | undefined {
  return Object.entries(headers).find(
    ([name]) => name.toLowerCase() === "content-type",
  )?.[1];
}

export const SessionElementBodyView: React.FC<SessionElementBodyViewProps> = ({
  title,
  headers,
  body,
  ...rest
}) => {
  const [language, setLanguage] = useState<string | undefined>(undefined);

  useEffect(() => {
    setLanguage(guessLanguageFromContentType(getContentType(headers)));
  }, [headers]);

  return (
    <Flex {...rest} vertical gap={8}>
      <div>{title}</div>
      <Editor
        className="qip-editor"
        language={language}
        value={body}
        options={{ readOnly: true }}
      />
    </Flex>
  );
};
