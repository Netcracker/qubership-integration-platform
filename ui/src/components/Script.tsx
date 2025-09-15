import React from "react";
import { Editor, Monaco } from "@monaco-editor/react";
import { editor, IRange, languages, Position } from "monaco-editor";
import { Flex } from "antd";

class GroovyCompletionProvider implements languages.CompletionItemProvider {
  constructor() {
    // Do nothing
  }

  public provideCompletionItems(
    model: editor.ITextModel,
    position: Position,
  ): languages.ProviderResult<languages.CompletionList> {
    const word = model.getWordUntilPosition(position);
    const range: IRange = {
      startLineNumber: position.lineNumber,
      endLineNumber: position.lineNumber,
      startColumn: word.startColumn,
      endColumn: word.endColumn,
    };

    const suggestions: languages.CompletionItem[] = [
      {
        label: "exchange",
        kind: languages.CompletionItemKind.Variable,
        insertText: "exchange",
        detail: "Exchange property",
        range,
      },
      {
        label: "getMessage",
        kind: languages.CompletionItemKind.Method,
        insertText: "getMessage()",
        detail: "Get message object",
        range,
      },
      {
        label: "setMessage",
        kind: languages.CompletionItemKind.Method,
        insertText: "setMessage($0)",
        insertTextRules: languages.CompletionItemInsertTextRule.InsertAsSnippet,
        detail: "Set message object",
        range,
      },
      {
        label: "getProperty",
        kind: languages.CompletionItemKind.Method,
        insertText: "getProperty($0)",
        insertTextRules: languages.CompletionItemInsertTextRule.InsertAsSnippet,
        detail: "Get message property",
        range,
      },
      {
        label: "setProperty",
        kind: languages.CompletionItemKind.Method,
        insertText: "setProperty($0)",
        insertTextRules: languages.CompletionItemInsertTextRule.InsertAsSnippet,
        detail: "Set message property",
        range,
      },
      {
        label: "removeProperty",
        kind: languages.CompletionItemKind.Method,
        insertText: "removeProperty($0)",
        insertTextRules: languages.CompletionItemInsertTextRule.InsertAsSnippet,
        detail: "Remove message property",
        range,
      },
    ];

    return { suggestions: suggestions };
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
  monaco.languages.setMonarchTokensProvider("groovy", {
    tokenizer: {
      root: [
        // Example of keywords and comments for basic highlighting
        [/(?:def|class|if|else|for|while|return)\b/, "keyword"],
        [/(\/\/.*$)|(\/\*[\s\S]*?\*\/)/, "comment"],
        [/"([^"\\]|\\.)*"/, "string"],
        [/\b\d+(\.\d+)?\b/, "number"],
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
  return (
    <Flex vertical {...props}>
      <Editor
        height="35vh"
        className="qip-editor"
        value={value}
        language={mode}
        onMount={(_editor, monaco) => {
          configureGroovyLanguage(monaco);
        }}
        onChange={(value) => {
          if (!readOnly) {
            onChange?.(value ?? "");
          }
        }}
        options={{
          fixedOverflowWidgets: true,
          readOnly: readOnly,
          scrollBeyondLastLine: false,
        }}
      />
    </Flex>
  );
};
