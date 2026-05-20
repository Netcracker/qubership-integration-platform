import React, { useRef, useEffect, useState } from "react";
import { Editor } from "@monaco-editor/react";
import { editor, languages, Position } from "monaco-editor";

type Monaco = typeof import("monaco-editor");
import {
  useMonacoTheme,
  useMonacoEditorOptions,
  applyVSCodeThemeToMonaco,
} from "../hooks/useMonacoTheme";
import {
  getRangeForWord,
  getTextBeforePosition,
} from "../misc/monaco/completion-helpers";
import {
  getGroovyCompletionItems,
  GROOVY_KEYWORDS,
} from "../misc/monaco/groovy-suggestions";
import { GROOVY_LANGUAGE_CONFIGURATION } from "../misc/monaco/language-config";
import { AutoHeight } from "./AutoHeight.tsx";

class GroovyCompletionProvider implements languages.CompletionItemProvider {
  public readonly triggerCharacters = ["."];

  public provideCompletionItems(
    model: editor.ITextModel,
    position: Position,
  ): languages.ProviderResult<languages.CompletionList> {
    const word = model.getWordUntilPosition(position);
    const range = getRangeForWord(position, word);
    const textBefore = getTextBeforePosition(model, position);
    return { suggestions: getGroovyCompletionItems(textBefore, range) };
  }
}

const GROOVY_COMPLETION_PROVIDER = new GroovyCompletionProvider();

function configureGroovyLanguage(monaco: Monaco): void {
  const isGroovyLanguageRegistered = monaco.languages
    .getLanguages()
    .some((language) => language.id === "groovy");
  if (isGroovyLanguageRegistered) {
    return;
  }
  monaco.languages.register({ id: "groovy" });
  monaco.languages.setLanguageConfiguration(
    "groovy",
    GROOVY_LANGUAGE_CONFIGURATION,
  );
  monaco.languages.setMonarchTokensProvider("groovy", {
    defaultToken: "invalid",
    tokenPostfix: ".groovy",

    keywords: [...GROOVY_KEYWORDS],

    operators: [
      "=",
      ">",
      "<",
      "!",
      "~",
      "?",
      ":",
      "==",
      "<=",
      ">=",
      "!=",
      "&&",
      "||",
      "++",
      "--",
      "+",
      "-",
      "*",
      "/",
      "&",
      "|",
      "^",
      "%",
      "<<",
      ">>",
      ">>>",
      "+=",
      "-=",
      "*=",
      "/=",
      "&=",
      "|=",
      "^=",
      "%=",
      "<<=",
      ">>=",
      ">>>=",
      "=>",
      "?.",
      "**",
    ],

    symbols: /[=><!~?:&|+*/^%-]+/,
    escapes:
      /\\(?:[abfnrtv\\"']|x[0-9A-Fa-f]{1,4}|u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})/,

    tokenizer: {
      root: [
        // Identifiers and keywords
        [
          /[a-z_$][\w$]*/,
          {
            cases: {
              "@keywords": "keyword",
              "@default": "identifier",
            },
          },
        ],
        [/[A-Z][\w$]*/, "type.identifier"],

        // Whitespace
        { include: "@whitespace" },

        // Delimiters and operators
        [/[{}()[\]]/, "@brackets"],
        [/[<>](?!@symbols)/, "@brackets"],
        [
          /@symbols/,
          {
            cases: {
              "@operators": "operator",
              "@default": "",
            },
          },
        ],

        // Numbers
        [/\d*\d+[eE]([+-]?\d+)?/, "number.float"],
        [/\d*\.\d+([eE][+-]?\d+)?/, "number.float"],
        [/0[xX][0-9a-fA-F']*[0-9a-fA-F]/, "number.hex"],
        [/0[0-7']*[0-7]/, "number.octal"],
        [/0[bB][0-1']*[0-1]/, "number.binary"],
        [/\d[\d']*/, "number"],
        [/\d/, "number"],

        // Delimiter: after number because of .\d floats
        [/;/, ""],
        [/[,.]/, "delimiter"],

        // Strings
        [/"""/, "string", "@string_triple_double"],
        [/'''/, "string", "@string_triple_single"],
        [/"([^"\\]|\\.)*$/, "string.invalid"],
        [/'([^'\\]|\\.)*$/, "string.invalid"],
        [/"/, "string", "@string_double"],
        [/'/, "string", "@string_single"],

        // Regex
        [/\/(?=([^/\\]|\\.)+\/)/, "regexp", "@regexp"],

        // Characters
        [/'[^\\']'/, "string"],
        [/(')(@escapes)(')/, ["string", "string.escape", "string"]],
        [/'/, "string.invalid"],
      ],

      whitespace: [
        [/[ \t\r\n]+/, ""],
        [/\/\*\*(?!\/)/, "comment.doc", "@javadoc"],
        [/\/\*/, "comment", "@comment"],
        [/\/\/.*$/, "comment"],
      ],

      comment: [
        [/[^/*]+/, "comment"],
        [/\*\//, "comment", "@pop"],
        [/[/*]/, "comment"],
      ],

      javadoc: [
        [/[^/*]+/, "comment.doc"],
        [/\*\//, "comment.doc", "@pop"],
        [/[/*]/, "comment.doc"],
      ],

      string_double: [
        [/[^\\"$]+/, "string"],
        [/@escapes/, "string.escape"],
        [/\\./, "string.escape.invalid"],
        [/\$\{/, "string.interpolated", "@string_interpolation"],
        [/\$[a-zA-Z_]\w*/, "string.interpolated"],
        [/"/, "string", "@pop"],
      ],

      string_single: [
        [/[^\\']+/, "string"],
        [/@escapes/, "string.escape"],
        [/\\./, "string.escape.invalid"],
        [/'/, "string", "@pop"],
      ],

      string_triple_double: [
        [/[^\\"$]+/, "string"],
        [/@escapes/, "string.escape"],
        [/\\./, "string.escape.invalid"],
        [/\$\{/, "string.interpolated", "@string_interpolation"],
        [/\$[a-zA-Z_]\w*/, "string.interpolated"],
        [/"""/, "string", "@pop"],
        [/./, "string"],
      ],

      string_triple_single: [
        [/[^\\']+/, "string"],
        [/@escapes/, "string.escape"],
        [/\\./, "string.escape.invalid"],
        [/'''/, "string", "@pop"],
        [/./, "string"],
      ],

      string_interpolation: [
        [/[^}]+/, "string.interpolated"],
        [/\}/, "string.interpolated", "@pop"],
      ],

      regexp: [
        [
          /(\{)(\d+(?:,\d*)?)(\})/,
          [
            "regexp.escape.control",
            "regexp.escape.control",
            "regexp.escape.control",
          ],
        ],
        [
          /(\[)(\^?)(?=(?:[^\]\\/ ]|\\.)+)/,
          ["regexp.escape.control", "regexp.escape.control"],
          "@regexrange",
        ],
        [
          /(\()(\?:|\?=|\?!)/,
          ["regexp.escape.control", "regexp.escape.control"],
        ],
        [/[()]/, "regexp.escape.control"],
        [/@escapes/, "regexp.escape"],
        [/[\\][^$]/, "regexp.escape"],
        [/[$]/, "regexp.escape"],
        [/[^\\/]/, "regexp"],
        [/\//, "regexp", "@pop"],
      ],

      regexrange: [
        [/-/, "regexp.escape.control"],
        [/@escapes/, "regexp.escape"],
        [/[^\]]/, "regexp"],
        [/\]/, "regexp.escape.control", "@pop"],
      ],
    },
  });
  monaco.languages.registerCompletionItemProvider(
    "groovy",
    GROOVY_COMPLETION_PROVIDER,
  );
}

export type ScriptProps = React.HTMLAttributes<HTMLElement> & {
  value: string;
  onChange?: (value: string) => void;
  mode?: "groovy" | "json";
  readOnly?: boolean;
};

export const Script: React.FC<ScriptProps> = ({
  value,
  onChange,
  mode = "groovy",
  readOnly = false,
  ...props
}): React.ReactNode => {
  const monacoTheme = useMonacoTheme();
  const editorFontOptions = useMonacoEditorOptions();
  const monacoRef = useRef<Monaco | null>(null);

  // Re-apply theme when it changes
  useEffect(() => {
    if (monacoRef.current) {
      applyVSCodeThemeToMonaco(monacoRef.current);
    }
  }, [monacoTheme]);

  const [overflowWidgetsDomNode] = useState<HTMLDivElement>(() => {
    const node = document.createElement("div");
    node.className = "monaco-editor";
    node.style.zIndex = "10000";
    return node;
  });

  useEffect(() => {
    document.body.appendChild(overflowWidgetsDomNode);
    return () => {
      overflowWidgetsDomNode.remove();
    };
  }, [overflowWidgetsDomNode]);

  return (
    <AutoHeight {...props}>
      <Editor
        height="100%"
        className="qip-editor"
        value={value}
        language={mode}
        theme={monacoTheme}
        beforeMount={(monaco: Monaco) => {
          configureGroovyLanguage(monaco);
          applyVSCodeThemeToMonaco(monaco);
        }}
        onMount={(_editor, monaco: Monaco) => {
          monacoRef.current = monaco;
        }}
        onChange={(value) => {
          if (!readOnly) {
            onChange?.(value ?? "");
          }
        }}
        options={{
          fixedOverflowWidgets: true,
          overflowWidgetsDomNode: overflowWidgetsDomNode,
          readOnly: readOnly,
          scrollBeyondLastLine: false,
          minimap: {
            enabled: false,
          },
          renderLineHighlight: "line",
          renderLineHighlightOnlyWhenFocus: true,
          fontSize: editorFontOptions.fontSize,
          lineHeight: editorFontOptions.lineHeight,
          fontFamily: editorFontOptions.fontFamily,
          fontWeight: editorFontOptions.fontWeight,
          automaticLayout: true,
          wordWrap: "off",
          wrappingIndent: "indent",
          tabSize: 2,
          insertSpaces: true,
          smoothScrolling: true,
          cursorBlinking: "smooth",
          cursorSmoothCaretAnimation: "on",
          contextmenu: true,
          mouseWheelZoom: false,
          quickSuggestions: {
            other: true,
            comments: false,
            strings: false,
          },
          suggestOnTriggerCharacters: true,
          wordBasedSuggestions: "currentDocument",
          suggest: {
            showKeywords: true,
            showSnippets: true,
            showWords: true,
            showFunctions: true,
            showVariables: true,
            showMethods: true,
          },
          bracketPairColorization: {
            enabled: true,
          },
        }}
      />
    </AutoHeight>
  );
};
