import React, { useEffect, useState } from "react";
import { Editor } from "@monaco-editor/react";
import { editor as editor_ } from "monaco-editor";

type SessionElementBodyViewProps = React.HTMLAttributes<HTMLElement> & {
  headers: Record<string, string>;
  body?: string;
};

export function guessLanguageFromContentType(
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

export function getContentType(
  headers: Record<string, string>,
): string | undefined {
  return Object.entries(headers).find(
    ([name]) => name.toLowerCase() === "content-type",
  )?.[1];
}

export async function formatDocumentInEditor(
  editor: editor_.IStandaloneCodeEditor | undefined,
) {
  editor?.updateOptions({ readOnly: false });
  await editor
    ?.getAction("editor.action.formatDocument")
    ?.run()
    .then(() => editor?.updateOptions({ readOnly: true }));
}

export function setUpDocumentFormatting(editor: editor_.IStandaloneCodeEditor) {
  const formatDocument = () => formatDocumentInEditor(editor);

  // on first initialization
  editor.onDidChangeModelLanguageConfiguration(formatDocument);

  // on every initialization
  editor.onDidLayoutChange(formatDocument);

  editor.onDidChangeModelContent(() => setTimeout(formatDocument, 1));
}

export const SessionElementBodyView: React.FC<SessionElementBodyViewProps> = ({
  headers,
  body,
}) => {
  const [language, setLanguage] = useState<string | undefined>(undefined);

  useEffect(() => {
    setLanguage(guessLanguageFromContentType(getContentType(headers)));
  }, [headers]);

  return (
    <Editor
      className="qip-editor"
      language={language}
      value={body}
      options={{ readOnly: true }}
      onMount={setUpDocumentFormatting}
    />
  );
};
