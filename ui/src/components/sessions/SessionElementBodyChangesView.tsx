import React, { useEffect, useRef, useState } from "react";
import { Col, Flex, Row, Switch } from "antd";
import {
  getContentType,
  guessLanguageFromContentType,
  SessionElementBodyView,
  setUpDocumentFormatting,
} from "./SessionElementBodyView.tsx";
import { DiffEditor, Monaco } from "@monaco-editor/react";
import { editor as editor_ } from "monaco-editor";
import { useMonacoTheme, applyVSCodeThemeToMonaco } from "../../hooks/useMonacoTheme";

export type SessionElementBodyChangesViewProps =
  React.HTMLAttributes<HTMLElement> & {
    headersBefore: Record<string, string> | undefined;
    headersAfter: Record<string, string> | undefined;
    bodyBefore: string | undefined;
    bodyAfter: string | undefined;
  };

export const SessionElementBodyChangesView: React.FC<
  SessionElementBodyChangesViewProps
> = ({ headersBefore, headersAfter, bodyBefore, bodyAfter, ...rest }) => {
  const [viewDiff, setViewDiff] = useState<boolean>(false);
  const [originalLanguage, setOriginalLanguage] = useState<string>();
  const [modifiedLanguage, setModifiedLanguage] = useState<string>();
  const monacoTheme = useMonacoTheme();
  const monacoRef = useRef<Monaco | null>(null);

  useEffect(() => {
    setOriginalLanguage(
      guessLanguageFromContentType(getContentType(headersBefore ?? {})),
    );
  }, [headersBefore]);

  useEffect(() => {
    setModifiedLanguage(
      guessLanguageFromContentType(getContentType(headersAfter ?? {})),
    );
  }, [headersAfter]);

  const handleDiffEditorDidMount = (
    editor: editor_.IStandaloneDiffEditor,
    monaco: Monaco,
  ) => {
    monacoRef.current = monaco;
    setUpDocumentFormatting(editor.getOriginalEditor());
    setUpDocumentFormatting(editor.getModifiedEditor());
    applyVSCodeThemeToMonaco(monaco);
  };

  useEffect(() => {
    if (monacoRef.current) {
      applyVSCodeThemeToMonaco(monacoRef.current);
    }
  }, [monacoTheme]);

  return (
    <Flex {...rest} vertical gap={8}>
      <Flex vertical={false} gap={8} justify="flex-end">
        <Switch value={viewDiff} onChange={(checked) => setViewDiff(checked)} />
        <span>View diff</span>
      </Flex>
      {viewDiff ? (
        <DiffEditor
          className="qip-editor"
          originalLanguage={originalLanguage}
          modifiedLanguage={modifiedLanguage}
          original={bodyBefore}
          modified={bodyAfter}
          theme={monacoTheme}
          options={{
            readOnly: true,
            originalAriaLabel: "Body Before",
            modifiedAriaLabel: "Body After",
          }}
          onMount={(editor, monaco) => handleDiffEditorDidMount(editor, monaco)}
        />
      ) : (
        <>
          <Row gutter={18}>
            <Col span={12}>
              <span>Body Before</span>
            </Col>
            <Col span={12}>
              <span>Body After</span>
            </Col>
          </Row>
          <Flex
            vertical={false}
            gap={8}
            justify="space-between"
            style={{ flexShrink: 1, flexGrow: 1 }}
          >
            <SessionElementBodyView
              style={{ flexGrow: 1, flexShrink: 1 }}
              headers={headersBefore ?? {}}
              body={bodyBefore ?? ""}
            />
            <SessionElementBodyView
              style={{ flexGrow: 1, flexShrink: 1 }}
              headers={headersAfter ?? {}}
              body={bodyAfter ?? ""}
            />
          </Flex>
        </>
      )}
    </Flex>
  );
};
