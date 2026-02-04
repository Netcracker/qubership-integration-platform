import {
  Constant,
  MappingAction,
  MappingDescription,
  MessageSchema,
  Transformation,
} from "../../mapper/model/model.ts";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Editor, Monaco } from "@monaco-editor/react";
import {
  useMonacoTheme,
  applyVSCodeThemeToMonaco,
} from "../../hooks/useMonacoTheme";
import {
  editor,
  languages,
  MarkerSeverity,
  Position,
  IRange,
  Token,
} from "monaco-editor";
import { MappingActions } from "../../mapper/actions-text/util.ts";
import { MappingUtil } from "../../mapper/util/mapping.ts";
import { MappingActions as Actions } from "../../mapper/util/actions.ts";
import { TRANSFORMATIONS } from "../../mapper/model/transformations.ts";
import {
  AttributeDetail,
  MessageSchemaUtil,
} from "../../mapper/util/schema.ts";
import { verifyMappingAction } from "../../mapper/verification/actions.ts";
import { VerificationError } from "../../mapper/verification/model.ts";
import { LocationRange } from "pegjs";

const MAPPER_ACTIONS_LANGUAGE_ID = "qip-mapper-actions";

const MAPPER_ACTIONS_LANGUAGE_CONFIGURATION: languages.LanguageConfiguration =
  {};

const MAPPER_ACTIONS_LANGUAGE_TOKENIZER: languages.IMonarchLanguage = {
  tokenizer: {
    start: [
      {
        regex: /body|header|property/,
        action: { token: "keyword.reference.$0", next: "path" },
      },
      {
        regex: /constant/,
        action: { token: "keyword.reference.constant", next: "constant" },
      },
      { regex: /[ \t]/, action: { token: "delimiter.space" } },
      { regex: /->/, action: { token: "operator.action" } },
      {
        regex: /:/,
        action: { token: "operator.transformation", next: "transformation" },
      },
      { regex: /[^ \t]+/, action: { token: "invalid" } },
    ],
    constant: [
      { regex: /\./, action: { token: "delimiter", next: "constantValue" } },
      { regex: /[ \t]/, action: { token: "invalid" } },
    ],
    path: [
      { regex: /\./, action: { token: "delimiter", next: "pathValue" } },
      { regex: /[ \t]/, action: { token: "invalid" } },
    ],
    pathValue: [
      { regex: /[^ .\t\r\n\\]/, action: { token: "string" } },
      { regex: /\\[ .\t\r\n\\]+/, action: { token: "string.escape" } },
      { regex: /\./, action: { token: "delimiter" } },
      { regex: /\.{2,}/, action: { token: "invalid" } },
      {
        regex: /[ \t\n]|$/,
        action: { token: "delimiter.space", next: "start" },
      },
    ],
    constantValue: [
      { regex: /[^ \t\r\n\\]/, action: { token: "string" } },
      { regex: /\\[ \t\r\n\\]/, action: { token: "string.escape" } },
      {
        regex: /[ \t\n]|$/,
        action: { token: "delimiter.space", next: "start" },
      },
    ],
    transformation: [
      {
        regex: /([^ .\t\r\n\\]|\\[ .\t\r\n\\])+/,
        action: {
          token: "identifier.transformation",
          next: "transformationParameters",
        },
      },
    ],
    transformationParameters: [
      { regex: /([^ \t\r\n\\]|\\[ \t\r\n\\])+/, action: { token: "string" } },
      { regex: /\n|$/, action: { token: "eoc", next: "start" } },
    ],
  },
  defaultToken: "invalid",
  start: "start",
  includeLF: true,
};

type MappingActionTextViewCompletionContext = {
  line: string;
  word: editor.IWordAtPosition;
  tokens: Token[];
  position: Position;
};

function hasToken(tokens: Token[], typePrefix: string): boolean {
  return tokens.some((token) => token.type.startsWith(typePrefix));
}

function getTokensBeforeOffset(tokens: Token[], offset: number): Token[] {
  return tokens.filter((token) => token.offset < offset);
}

function isOnSourcePosition(
  context: MappingActionTextViewCompletionContext,
): boolean {
  const tokensBefore = getTokensBeforeOffset(
    context.tokens,
    context.position.column,
  );
  return !hasToken(tokensBefore, "operator.action");
}

function isOnTargetPosition(
  context: MappingActionTextViewCompletionContext,
): boolean {
  const tokensBefore = getTokensBeforeOffset(
    context.tokens,
    context.position.column,
  );
  return !hasToken(tokensBefore, "operator.transformation");
}

function isOnTransformationPosition(
  context: MappingActionTextViewCompletionContext,
): boolean {
  const tokensBefore = getTokensBeforeOffset(
    context.tokens,
    context.position.column,
  );
  let i = tokensBefore.length - 1;
  while (i >= 0) {
    const type = tokensBefore[i].type;
    if (!type.startsWith("invalid")) {
      return (
        type.startsWith("operator.transformation") ||
        type.startsWith("identifier.transformation")
      );
    }
    i--;
  }
  return false;
}

function getTransformationSuggestions(
  context: MappingActionTextViewCompletionContext,
): languages.CompletionItem[] {
  const range = {
    startLineNumber: context.position.lineNumber,
    endLineNumber: context.position.lineNumber,
    startColumn: context.word.startColumn,
    endColumn: context.word.endColumn,
  };
  return TRANSFORMATIONS.map((transformation) => ({
    label: transformation.name,
    kind: languages.CompletionItemKind.Function,
    insertText: transformation.name,
    documentation: transformation.title,
    range: range,
  }));
}

function getReferencesToAllAttributes(
  schema: MessageSchema,
): AttributeDetail[] {
  const result: AttributeDetail[] = [];
  MessageSchemaUtil.findAttribute(schema, (_attribute, kind, path) => {
    result.push({ kind, path, definitions: [] });
    return false;
  });
  return result;
}

function escape(value: string, charactersToEscape: string): string {
  return [...value]
    .map((i) => (charactersToEscape.indexOf(i) >= 0 ? `\\${i}` : i))
    .join("");
}

function getReferenceStartColumn(
  context: MappingActionTextViewCompletionContext,
): number {
  const tokensBefore = getTokensBeforeOffset(
    context.tokens,
    context.position.column,
  );
  let i = tokensBefore.length - 1;
  let offset = 0;
  while (i >= 0 && !tokensBefore[i].type.startsWith("delimiter.space")) {
    offset = tokensBefore[i].offset;
    i--;
  }
  return offset + 1;
}

function getReferenceEndColumn(
  context: MappingActionTextViewCompletionContext,
): number {
  if (context.word.word === "") {
    return context.position.column;
  }
  let offset = context.line.length - 1;
  let i = context.tokens.length - 1;
  while (i >= 0 && context.tokens[i].offset > context.position.column) {
    if (
      context.tokens[i].type.startsWith("invalid") ||
      context.tokens[i].type.startsWith("delimiter.space")
    ) {
      offset = context.tokens[i].offset;
    }
    i--;
  }
  return offset + 1;
}

function getReferenceRange(
  context: MappingActionTextViewCompletionContext,
): IRange {
  return {
    startLineNumber: context.position.lineNumber,
    endLineNumber: context.position.lineNumber,
    startColumn: getReferenceStartColumn(context),
    endColumn: getReferenceEndColumn(context),
  };
}

function getTargetCompletions(
  context: MappingActionTextViewCompletionContext,
  mapping: MappingDescription,
): languages.CompletionItem[] {
  return getAttributeCompletions(
    context,
    getReferencesToAllAttributes(mapping.target),
  );
}

function getAttributeCompletions(
  context: MappingActionTextViewCompletionContext,
  attributes: AttributeDetail[],
): languages.CompletionItem[] {
  const range = getReferenceRange(context);
  return attributes
    .filter((detail) => detail.kind && detail.path.every((a) => a.name))
    .map((detail) => {
      const value = [detail.kind, ...detail.path.map((a) => a.name)]
        .map((i) => escape(i, " .\t\r\n\\"))
        .join(".");
      return {
        label: value,
        kind: languages.CompletionItemKind.Variable,
        insertText: value,
        range: range,
      };
    });
}

function getConstantCompletions(
  context: MappingActionTextViewCompletionContext,
  constants: Constant[],
): languages.CompletionItem[] {
  const range = getReferenceRange(context);
  return constants
    .filter((constant) => constant.name)
    .map((constant) => {
      const value = `constant.${escape(constant.name, " \t\r\n\\")}`;
      return {
        label: value,
        kind: languages.CompletionItemKind.Constant,
        insertText: value,
        range: range,
      };
    });
}

function getSourceCompletions(
  context: MappingActionTextViewCompletionContext,
  mapping: MappingDescription,
): languages.CompletionItem[] {
  return [
    ...getAttributeCompletions(
      context,
      getReferencesToAllAttributes(mapping.source),
    ),
    ...getConstantCompletions(context, mapping.constants),
  ];
}

class MappingActionsTextViewCompletionProvider
  implements languages.CompletionItemProvider
{
  constructor(
    private monaco: Monaco,
    private mapping: MappingDescription,
  ) {}

  public provideCompletionItems(
    model: editor.ITextModel,
    position: Position,
  ): languages.ProviderResult<languages.CompletionList> {
    const word = model.getWordUntilPosition(position);
    const line = model.getLineContent(position.lineNumber);
    const tokens = this.monaco.editor.tokenize(
      line,
      MAPPER_ACTIONS_LANGUAGE_ID,
    )[0];
    const completionContext: MappingActionTextViewCompletionContext = {
      line,
      word,
      tokens,
      position,
    };

    const suggestions = isOnSourcePosition(completionContext)
      ? getSourceCompletions(completionContext, this.mapping)
      : isOnTargetPosition(completionContext)
        ? getTargetCompletions(completionContext, this.mapping)
        : isOnTransformationPosition(completionContext)
          ? getTransformationSuggestions(completionContext)
          : [];

    return { suggestions: suggestions };
  }
}

function getActionLocation(action: MappingAction): LocationRange {
  const defaultLocation = {
    start: { column: 0, line: 1, offset: 0 },
    end: { column: 0, line: 1, offset: 0 },
  };
  return (
    (action?.sources.find((source) => !!source)?.metadata
      ?.location as LocationRange) ?? defaultLocation
  );
}

function verifyMappingActions(
  mapping: MappingDescription,
): editor.IMarkerData[] {
  return mapping.actions
    .map(
      (action) =>
        [action, verifyMappingAction(action, mapping)] as [
          MappingAction,
          VerificationError[],
        ],
    )
    .filter(([, errors]) => errors.length > 0)
    .map(([action, errors]) => {
      const locationRange = getActionLocation(action);
      const text = errors.map((error) => error.message).join("\n");
      return {
        severity: MarkerSeverity.Error,
        message: text,
        startLineNumber: locationRange.start.line,
        startColumn: locationRange.start.column,
        endLineNumber: locationRange.end.line,
        endColumn: locationRange.end.column,
      };
    });
}

function configureMapperActionsLanguage(
  editor: editor.IStandaloneCodeEditor,
  monaco: Monaco,
  mapping: MappingDescription,
  onChange?: (mapping: MappingDescription) => void,
) {
  const alreadyRegistered = monaco.languages
    .getLanguages()
    .some((language) => language.id === MAPPER_ACTIONS_LANGUAGE_ID);
  if (alreadyRegistered) {
    console.log(`Language already registered: ${MAPPER_ACTIONS_LANGUAGE_ID}`);
    return;
  } else {
    monaco.languages.register({ id: MAPPER_ACTIONS_LANGUAGE_ID });
    monaco.languages.setLanguageConfiguration(
      MAPPER_ACTIONS_LANGUAGE_ID,
      MAPPER_ACTIONS_LANGUAGE_CONFIGURATION,
    );
    monaco.languages.setMonarchTokensProvider(
      MAPPER_ACTIONS_LANGUAGE_ID,
      MAPPER_ACTIONS_LANGUAGE_TOKENIZER,
    );
  }
  const completionItemProviderHandle =
    monaco.languages.registerCompletionItemProvider(
      MAPPER_ACTIONS_LANGUAGE_ID,
      new MappingActionsTextViewCompletionProvider(monaco, mapping),
    );
  editor.onDidDispose(() => {
    completionItemProviderHandle.dispose();
  });
  editor.onDidChangeModelContent(() => {
    const model = editor.getModel();
    if (!model) {
      return;
    }
    const result = MappingActions.updateFromString(editor.getValue(), mapping);
    const markers: editor.IMarkerData[] = result.errors.map((error) => ({
      severity: MarkerSeverity.Error,
      message: error.message,
      startLineNumber: error.location.start.line,
      startColumn: error.location.start.column,
      endLineNumber: error.location.end.line,
      endColumn: error.location.end.column,
    }));
    const actionVerificationMarkers = verifyMappingActions(result.mapping);
    monaco.editor.setModelMarkers(model, MAPPER_ACTIONS_LANGUAGE_ID, [
      ...markers,
      ...actionVerificationMarkers,
    ]);
    onChange?.(result.mapping);
  });
}

function transformationsAreEqual(
  transformation1: Transformation | undefined,
  transformation2: Transformation | undefined,
): boolean {
  return (
    transformation1 === transformation2 ||
    (transformation1?.name === transformation2?.name &&
      transformation1?.parameters?.length ===
        transformation2?.parameters?.length &&
      !!transformation1?.parameters?.every(
        (parameter, index) =>
          parameter === transformation2?.parameters?.[index],
      ))
  );
}

function actionsAreEqual(
  action1: MappingAction,
  action2: MappingAction,
): boolean {
  return (
    action1 === action2 ||
    (action1.id === action2.id &&
      Actions.referencesAreEqual(action1.target, action2.target) &&
      action1.sources.length === action2.sources.length &&
      action1.sources.every((source, index) =>
        Actions.referencesAreEqual(source, action2.sources[index]),
      ) &&
      transformationsAreEqual(action1.transformation, action2.transformation))
  );
}

function actionsAreTheSame(
  actions1: MappingAction[],
  actions2: MappingAction[],
): boolean {
  if (actions1.length !== actions2.length) {
    return false;
  }
  const sortedActions1 = actions1.sort((a, b) => a.id.localeCompare(b.id));
  const sortedActions2 = actions2.sort((a, b) => a.id.localeCompare(b.id));
  return sortedActions1.every((action, index) =>
    actionsAreEqual(action, sortedActions2[index]),
  );
}

export type MappingActionsTextViewProps = {
  mapping?: MappingDescription;
  onChange?: (mapping: MappingDescription) => void;
};

export const MappingActionsTextView: React.FC<MappingActionsTextViewProps> = ({
  mapping,
  onChange,
}) => {
  const [value, setValue] = useState<string>("");
  const [actions, setActions] = useState<MappingAction[]>([]);
  const monacoRef = useRef<Monaco | null>(null);

  useEffect(() => {
    if (actionsAreTheSame(actions, mapping?.actions ?? [])) {
      return;
    }
    setValue(mapping ? MappingActions.toString(mapping) : "");
  }, [actions, mapping]);

  const onEditorMount = useCallback(
    (editor: editor.IStandaloneCodeEditor, monaco: Monaco) => {
      monacoRef.current = monaco;
      configureMapperActionsLanguage(
        editor,
        monaco,
        mapping ?? MappingUtil.emptyMapping(),
        (mappingDescription) => {
          setActions(mappingDescription.actions);
          onChange?.(mappingDescription);
        },
      );
      applyVSCodeThemeToMonaco(monaco);
    },
    [mapping, onChange],
  );

  const monacoTheme = useMonacoTheme();
  useEffect(() => {
    if (monacoRef.current) {
      applyVSCodeThemeToMonaco(monacoRef.current);
    }
  }, [monacoTheme]);

  return (
    <Editor
      height="35vh"
      className="qip-editor"
      value={value}
      language={MAPPER_ACTIONS_LANGUAGE_ID}
      theme={monacoTheme}
      onMount={(editor, monaco) => onEditorMount(editor, monaco)}
      options={{ fixedOverflowWidgets: true }}
    />
  );
};
