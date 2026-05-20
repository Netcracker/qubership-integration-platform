import { Editor, OnMount } from "@monaco-editor/react";
import styles from "../../components/elements_library/ElementsLibrarySidebar.module.css";
import { useElementsAsCode } from "../../hooks/useElementsAsCode";
import React, { useCallback, useRef } from "react";
import {
  applyVSCodeThemeToMonaco,
  useMonacoTheme,
} from "../../hooks/useMonacoTheme";
import { Element } from "../../api/apiTypes.ts";
import { useStore } from "@xyflow/react";
import type { editor } from "monaco-editor";
import { buildElementsSignature } from "../../misc/element-code-utils";
import { useElementCodeHighlight } from "../../hooks/useElementCodeHighlight";

interface ChainTextViewPanelProps {
  chainId: string;
  elements: Element[];
}

const shallowStringArrayEqual = (
  a: readonly string[],
  b: readonly string[],
): boolean =>
  a.length === b.length && a.every((value, index) => value === b[index]);

export const ChainTextViewPanel: React.FC<ChainTextViewPanelProps> = ({
  chainId,
  elements,
}) => {
  const { elementAsCode } = useElementsAsCode(
    chainId,
    buildElementsSignature(elements),
  );
  const monacoTheme = useMonacoTheme();
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);

  const textViewContent =
    typeof elementAsCode?.code === "string" ? elementAsCode.code : "";

  const selectedElementIds = useStore<string[]>((state) => {
    const ids: string[] = [];
    state.nodeLookup.forEach((node) => {
      if (node.selected) ids.push(node.id);
    });
    ids.sort((a, b) => a.localeCompare(b));
    return ids;
  }, shallowStringArrayEqual);

  useElementCodeHighlight({
    editorRef,
    selectedIds: selectedElementIds,
    content: textViewContent,
  });

  // useMonacoTheme re-applies the VS Code theme to every registered Monaco
  // instance on theme changes, so we only need to register on mount.
  const handleMount = useCallback<OnMount>((editorInstance, monaco) => {
    editorRef.current = editorInstance;
    if (monaco) {
      applyVSCodeThemeToMonaco(monaco);
    }
  }, []);

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
        onMount={handleMount}
      />
    </div>
  );
};
