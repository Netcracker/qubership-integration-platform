import React, { useCallback, useEffect, useRef, useState } from "react";
import { Editor } from "@monaco-editor/react";
import type * as monacoNs from "monaco-editor";
import type { editor, languages } from "monaco-editor";
type Monaco = typeof monacoNs;
import { LocationRange } from "pegjs";
import {
  KeyValuePair,
  makeArray,
  parse,
  removeDuplicateKeys,
} from "../../../misc/key-value-text-util.ts";
import {
  applyVSCodeThemeToMonaco,
  useMonacoTheme,
} from "../../../hooks/useMonacoTheme.ts";
import { isParseError } from "../../../mapper/actions-text/parser.ts";
import { KeyValuePairsEditorProps } from "./KeyValuePairsTable.tsx";

const KEY_VALUE_PAIRS_LANGUAGE_ID = "cip-kv-pairs";

const KEY_VALUE_PAIRS_LANGUAGE_CONFIGURATION: languages.LanguageConfiguration =
  {};

const KEY_VALUE_PAIRS_LANGUAGE_TOKENIZER: languages.IMonarchLanguage = {
  tokenizer: {
    start: [
      { regex: /[ \t\r\n]+/, action: { token: "white" } },
      { regex: /([^=;\\]|\\[=;\\])+/, action: { token: "string" } },
      { regex: /[=;]/, action: { token: "delimiter" } },
    ],
  },
  defaultToken: "invalid",
  start: "start",
  includeLF: true,
};

function configureKeyValuePairsLanguage(monaco: Monaco) {
  const alreadyRegistered = monaco.languages
    .getLanguages()
    .some(
      (language: { id: string }) => language.id === KEY_VALUE_PAIRS_LANGUAGE_ID,
    );
  if (alreadyRegistered) {
    console.log(`Language already registered: ${KEY_VALUE_PAIRS_LANGUAGE_ID}`);
    return;
  }
  monaco.languages.register({
    id: KEY_VALUE_PAIRS_LANGUAGE_ID,
  });
  monaco.languages.setLanguageConfiguration(
    KEY_VALUE_PAIRS_LANGUAGE_ID,
    KEY_VALUE_PAIRS_LANGUAGE_CONFIGURATION,
  );
  monaco.languages.setMonarchTokensProvider(
    KEY_VALUE_PAIRS_LANGUAGE_ID,
    KEY_VALUE_PAIRS_LANGUAGE_TOKENIZER,
  );
}

function formatPairs(pairs: KeyValuePair[]): string {
  return makeArray(pairs)
    .map((pair) => `${pair};`)
    .join("\n");
}

export type KeyValuePairsTextEditorProps = KeyValuePairsEditorProps & {
  // Receives the parse error of the text, or undefined once the text parses.
  // While the text does not parse, value keeps the last pairs that did.
  onErrorChange?: (error: string | undefined) => void;
};

export const KeyValuePairsTextEditor: React.FC<
  KeyValuePairsTextEditorProps
> = ({ onChange, onErrorChange, value }) => {
  const [text, setText] = useState<string>("");
  // The text of the pairs last passed to onChange or last taken from value. A
  // value that formats to it brings no new pairs, so the typed text stays as
  // it is, including text that does not parse yet.
  const syncedTextRef = useRef<string>();

  const editorRef = useRef<editor.IStandaloneCodeEditor>();
  const monacoRef = useRef<Monaco>();
  const monacoTheme = useMonacoTheme();
  useEffect(() => {
    if (monacoRef.current) {
      applyVSCodeThemeToMonaco(monacoRef.current);
    }
  }, [monacoTheme]);

  const setMarkers = useCallback((markers: editor.IMarkerData[]) => {
    const model = editorRef.current?.getModel();
    if (!model) {
      return;
    }
    monacoRef.current?.editor?.setModelMarkers(
      model,
      KEY_VALUE_PAIRS_LANGUAGE_ID,
      markers,
    );
  }, []);

  const setMarker = useCallback(
    (locationRange: LocationRange, message: string) => {
      const monaco = monacoRef.current;
      if (!monaco) {
        return;
      }
      const markers: editor.IMarkerData[] = [
        {
          severity: monaco.MarkerSeverity.Error,
          message,
          startLineNumber: locationRange.start.line,
          startColumn: locationRange.start.column,
          endLineNumber: locationRange.end.line,
          endColumn: locationRange.end.column,
        },
      ];
      setMarkers(markers);
    },
    [setMarkers],
  );

  const clearMarkers = useCallback(() => {
    setMarkers([]);
  }, [setMarkers]);

  useEffect(() => {
    const formatted = formatPairs(value ?? []);
    if (formatted !== syncedTextRef.current) {
      syncedTextRef.current = formatted;
      setText(formatted);
      clearMarkers();
      onErrorChange?.(undefined);
    }
  }, [value, clearMarkers, onErrorChange]);

  return (
    <Editor
      className="cip-editor"
      wrapperProps={{ "data-testid": "key-value-pairs-editor" }}
      theme={monacoTheme}
      value={text}
      language={KEY_VALUE_PAIRS_LANGUAGE_ID}
      onMount={(editor, monaco: Monaco) => {
        editorRef.current = editor;
        monacoRef.current = monaco;
        configureKeyValuePairsLanguage(monaco);
        applyVSCodeThemeToMonaco(monaco);
      }}
      onChange={(value) => {
        const v = value ?? "";
        setText(v);
        try {
          const parsed = removeDuplicateKeys(parse(v));
          syncedTextRef.current = formatPairs(parsed);
          clearMarkers();
          onErrorChange?.(undefined);
          onChange?.(parsed);
        } catch (error) {
          if (isParseError(error)) {
            setMarker(error.location, error.message);
            onErrorChange?.(error.message);
          }
        }
      }}
      options={{
        fixedOverflowWidgets: true,
      }}
    />
  );
};
