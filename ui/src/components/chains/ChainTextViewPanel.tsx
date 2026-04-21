import { Editor, Monaco } from "@monaco-editor/react";
import styles from "../../components/elements_library/ElementsLibrarySidebar.module.css";
import { useElementsAsCode } from "../../hooks/useElementsAsCode";
import React, { useEffect, useRef, useState } from "react";
import {
  applyVSCodeThemeToMonaco,
  useMonacoTheme,
} from "../../hooks/useMonacoTheme";
import { Element } from "../../api/apiTypes.ts";

interface ChainTextViewPanelProps {
  chainId: string;
  elements: Element[];
}

export const ChainTextViewPanel: React.FC<ChainTextViewPanelProps> = ({
  chainId,
  elements,
}) => {
  const [textViewContent, setTextViewContent] = useState<string>("");
  const [elementAsCodeTimestamp, setElementAsCodeTimestamp] =
    useState<number>();
  const { elementAsCode } = useElementsAsCode(chainId, elementAsCodeTimestamp);
  const monacoTheme = useMonacoTheme();
  const monacoRef = useRef<Monaco | null>(null); // eslint-disable-line @typescript-eslint/no-redundant-type-constituents -- Monaco from @monaco-editor/react may include any in union

  useEffect(() => {
    if (elementAsCode?.code != null && typeof elementAsCode.code === "string") {
      setTextViewContent(elementAsCode.code);
    }
  }, [elementAsCode]);

  useEffect(() => {
    if (monacoRef.current) {
      applyVSCodeThemeToMonaco(monacoRef.current);
    }
  }, [monacoTheme]);

  useEffect(() => {
    setElementAsCodeTimestamp(Date.now());
  }, [elements]);

  return (
    <div className={`${styles.rightPanelCodeBlock} qip-editor`}>
      <Editor
        height="100%"
        language="yaml"
        value={textViewContent}
        theme={monacoTheme}
        options={{
          readOnly: true,
          folding: true,
          fixedOverflowWidgets: true,
          minimap: { enabled: false },
          scrollBeyondLastLine: false,
        }}
        onMount={(_, monaco) => {
          monacoRef.current = monaco ?? null;
          if (monaco) {
            applyVSCodeThemeToMonaco(monaco);
          }
        }}
      />
    </div>
  );
};
