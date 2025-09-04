import React, {
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { TransformationContext } from "../../TransformationEditDialog.tsx";
import { Editor, Monaco } from "@monaco-editor/react";
import {
  editor,
  languages,
  MarkerSeverity,
  Position,
  IRange,
  Uri,
  IMarkdownString,
} from "monaco-editor";
import {
  Constant,
} from "../../../../mapper/model/model.ts";
import { validateExpression } from "../../../../mapper/expressions/validation.ts";
import { MappingUtil } from "../../../../mapper/util/mapping.ts";
import { AttributeDetail } from "../../../../mapper/util/schema.ts";
import { MappingActions } from "../../../../mapper/util/actions.ts";
import { buildAttributeReferenceText, buildConstantReferenceText } from "../../../../mapper/expressions/references.ts";

const MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID =
  "qip-mapper-transformation-expression";

const MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_CONFIGURATION: languages.LanguageConfiguration =
  {};

const MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_TOKENIZER: languages.IMonarchLanguage =
  {
    tokenizer: {
      start: [
        { regex: /[ \t\r\n]+/, action: { token: "white" } },
        { regex: /--[^\r\n]*[\r\n]/, action: { token: "comment" } },
        { regex: /\/\*/, action: { token: "comment", next: "blockComment" } },

        {
          regex: /'([^'\\]|\\.)*([\r\n]|$)/,
          action: { token: "string.invalid" },
        },
        {
          regex: /'/,
          action: { token: "string.quote", bracket: "@open", next: "string" },
        },

        { regex: /[()]/, action: { token: "@brackets" } },
        {
          regex: /[+\-*\\!><]|&&|\|\||==|!=|>=|<=|\/|%/,
          action: { token: "operator" },
        },

        { regex: /[1-9][0-9]*[lL]?/, action: { token: "number" } },
        { regex: /0[xX][0-9a-fA-F]+/, action: { token: "number.hex" } },
        { regex: /0[0-7]*/, action: { token: "number.octal" } },
        {
          regex: /(?:\d+\.)?\d+(?:[eE][-+]?\d+)?/,
          action: { token: "number.float" },
        },
        { regex: /\b(?:true|false|null)\b/, action: { token: "constant" } },

        {
          regex: /\b(?:body|header|property)\b/,
          action: { token: "keyword" },
        },
        {
          regex: /\bconstant\b/,
          action: { token: "keyword" },
        },

        {
          regex: /(?:[a-zA-Z_]|\\.)(?:[\w_]|\\.)*/,
          action: { token: "identifier" },
        },

        { regex: /[,.]/, action: { token: "delimiter" } },

        { regex: /./, action: { token: "invalid" } },
      ],
      blockComment: [
        { regex: /[^/*]+/, action: { token: "comment" } },
        { regex: /\/\*/, action: { token: "comment", next: "@push" } },
        { regex: /\*\//, action: { token: "comment", next: "@pop" } },
        { regex: /[/*]/, action: { token: "comment" } },
      ],
      string: [
        { regex: /[^'\\]/, action: { token: "string" } },
        { regex: /\\[rnt']/, action: { token: "string.escape" } },
        { regex: /\\./, action: { token: "string.escape.invalid" } },
        {
          regex: /'/,
          action: { token: "string.quote", bracket: "@close", next: "@pop" },
        },
      ],
    },
    defaultToken: "invalid",
    start: "start",
    includeLF: true,
  };

type ExpressionReferences = {
  attributes: AttributeDetail[];
  constants: Constant[];
};

class TransformationExpressionCompletionProvider
  implements languages.CompletionItemProvider
{
  private readonly PREDEFINED_FUNCTIONS: {
    label: string;
    snippet: string;
    documentation: IMarkdownString;
  }[] = [
    {
      label: "if",
      snippet: "if ( ${1:boolean-expression}$0, ${2:then}, ${3:else} )",
      documentation: {
        value: `
if ( *boolean-expression*, *then*, *else* )

#### Description
When boolean-expression evaluates to true,
the data mapper returns then.

When boolean-expression evaluates to false,
the data mapper returns else.

All three arguments are required. The last argument can be null,
which means that nothing is mapped when boolean-expression
evaluates to false.

#### Parameters

* __boolean-expression__ — expression of boolean type
* __then__ — expression which is returned if boolean-expression evaluates to true
* __else__ — expression which is returned if boolean-expression evaluates to false
`,
      },
    },
    {
      label: "isempty",
      snippet: "isempty ( ${1:source-field-name}$0 )",
      documentation: {
        value: `
isempty ( *source-field-name* )

#### Description

The result of the isempty() function is a Boolean value.
Specify at least one argument, which is the name of a source
field in the mapping that you want to apply the condition to.

When the specified source field is empty, the isempty() function returns true.

#### Parameters
* __source-field-name__ — source field that needs to be checked for emptiness
  or concatenation of several source fields

#### Example

Optionally, add the + (concatenation) operator with an additional field for example:

\`\`\`
isempty(body.lastName + body.firstName)
\`\`\`
        `,
      },
    },
    {
      label: "tolower",
      snippet: "tolower ( ${1:source-field}$0 )",
      documentation: {
        value: `
tolower ( *source-field* )

#### Description

Converts the given source string field to lowercase.

#### Parameters

* __source-field__ — the source field to be converted to lowercase

#### Example

To convert the source field "my_source_field" to lowercase,
you can use the following expression:

\`\`\`
tolower(body.my_source_field)
\`\`\`
`,
      },
    },
    {
      label: "filterBy",
      snippet: "filterBy ( ${1:source-array}$0, ${2:filtering-expression} )",
      documentation: {
        value: `
filterBy ( *source-array*, *filtering-expression* )

#### Description

Pick all objects from the source array that match
the filtering-expression.

#### Parameters

* __source-array__ — the source array that will be filtered
* __filtering-expression__ — the expression that specifies
  filtering conditions for objects in the source array

#### Example

To pick all objects from the "array_of_objects" source array,
where field "value" is bigger than "4"
(mentioned fields shall be connected),
you can use the following expression:

\`\`\`
filterBy(
  body.array_of_objects,
  body.array_of_objects.value
    > constant.4
)
\`\`\`
`,
      },
    },
    {
      label: "getFirst",
      snippet: "getFirst ( ${1:source-array}$0 )",
      documentation: {
        value: `
getFirst ( *source-array* )

#### Description

Gets the first object from the array.

#### Parameters

* __source-array__ — the source array from which to get the first element

##### Example

To get the first object from the source array of objects
"array_of_objects", you can use the following expression:

\`\`\`
getFirst(body.array_of_objects)
\`\`\`
`,
      },
    },
    {
      label: "trim",
      snippet: "trim ( ${1:source-field}$0 )",
      documentation: {
        value: `
trim ( *source-field* )

#### Description

Trims leading and trailing whitespace from a string.

#### Parameters

* __source-field__ — the source string field where you want
  to trim leading and trailing spaces

#### Example

To trim leading and trailing whitespace from the source field
"my_string_field", you can use the following expression:

\`\`\`
trim(body.my_string_field)
\`\`\`
`,
      },
    },
    {
      label: "replaceAll",
      snippet:
        "replaceAll ( ${1:source-field}$0, '${2:pattern}', '${3:replacement}' )",
      documentation: {
        value: `
replaceAll ( *source-field*, *pattern*, *replacement* )

#### Description

Returns a new string from source-field
with all matches of a pattern
replaced by a replacement.

#### Parameters

* __source-field__ — source field where the replacement should be applied
* __pattern__ — regular expression pattern specifying the part(s)
  of the source-field to be replaced
* __replacement__ — string, specifying the replacement
  for the parts matching the regular expression in the pattern

#### Example

To put every word and underscores from the string in the
round brackets, you can use the following expression:

\`\`\`
replaceAll(
  body.string_field,
  '(\\w[\\w\\d_]*)',
  '($1)'
)
\`\`\`
`,
      },
    },
    {
      label: "map",
      snippet:
        "map ( ${1:source-array}$0, ${2:mapping-transformation-expression} )",
      documentation: {
        value: `
map ( *source-array*, *mapping-transformation-expression* )

#### Description

Maps the source-array to other target field representation,
by applying the mapping-transformation-expression.

#### Parameters

* __source-array__ — source array for which the mapping should be applied
* __mapping-transformation-expression__ — transformation expression that
  will be applied to each element of the source array

#### Example

To fetch "firstName" field of each customer object from
"customers" source array and map it to the concatenation
of "SIM Card for " prefix and "firstName" source field,
the following expression can be used:

\`\`\`
map(
  body.customers.firstName,
  'SIM Card for '
    + body.customers.firstName
)
\`\`\`
`,
      },
    },
    {
      label: "sort",
      snippet: "sort ( ${1:source-array}$0, ${2:sort-by-field} )",
      documentation: {
        value: `
sort ( *source-array*, *sort-by-field* )

#### Description

Sorts input array source-array by the field
specified in sort-by-field parameter.

#### Parameters

* __source-array__ — original source array that needs to be sorted
* __sort-by-field__ — field that will be used to sort the original array

#### Example

To sort an array of customers by each customer's name in
alphabetical order, you can use the following expression:

\`\`\`
sort(
  body.customers,
  body.customers.name
)
\`\`\`
`,
      },
    },
    {
      label: "getKeys",
      snippet: "getKeys ( ${1:source-object}$0 )",
      documentation: {
        value: `
getKeys ( *source-object* )

#### Description

Picks all field names (keys) from the object.

#### Parameters

* __source-object__ — the object to pick field names (keys) from

#### Example

To pick all field names (keys) from the source object "my_object",
you can use the following expression:

\`\`\`
getKeys(body.my_object)
\`\`\`
            `,
      },
    },
    {
      label: "getValues",
      snippet: "getValues ( ${1:source-object}$0 )",
      documentation: {
        value: `
getValues ( *source-object* )

#### Description

Picks all field values from the object.

#### Parameters

* __source-object__ — the object to pick field values from

#### Example

To pick all field values from the source object "my_object",
you can use the following expression:

\`\`\`
getValues(body.my_object)
\`\`\`
            `,
      },
    },
    {
      label: "formatDateTime",
      snippet:
        "formatDateTime ( ${1:dateTimeFormat}$0, ${2:year}, ${3:month}, ${4:day}, ${5:hours}, ${6:minutes}, ${7:seconds}, ${8:milliseconds}, ${9:timezone}, ${10:locale} )",
      documentation: {
        value: `
formatDateTime ( *dateTimeFormat*, *year*, *month*, *day*, *hours*, *minutes*, *seconds*, *milliseconds*, *timezone*, *locale* )

#### Description

Builds complex datetime according to the specified format dateTimeFormat.

#### Parameters

* __dateTimeFormat__ — Datetime format, for example 'yyyy-MM-dd HH:mm:ss.SSSZ'
* __year__ — constant or field path to get year
* __month__ — constant or field path to get month
* __day__ — constant or field path to get day
* __hours__ — constant or field path to get hours
* __minutes__ — constant or field path to get minutes
* __seconds__ — constant or field path to get seconds
* __milliseconds__ — constant or field path to get milliseconds
* __timezone__ — constant or field path to get timezone
* __locale__ — constant or field path to get locale

#### Example

To build datetime in format 'yyyy-MM-dd' via values from the
source fields "year", "month" and "dayOfMonth",
the following expression can be used:

\`\`\`
formatDateTime(
  'yyyy-MM-dd',
  body.year,
  body.month,
  body.dayOfMonth
)
\`\`\`
            `,
      },
    },
    {
      label: "list",
      snippet:
        "list ( ${1:source-field/object/array}$0, ${2:source-field/object/array} )",
      documentation: {
        value: `
list ( *source-field/object/array*, *source-field/object/array* )

#### Description

Create a new array from the source primitives, objects and arrays.
It is possible to apply additional functions for
source-field/object/array.

#### Parameters

* __source-field/object/array__ — path to the source field, object or array of primitives

#### Example

To build new array from the source "completedOrders" array
of objects and "currentOrder" object, the following expression
can be used:

\`\`\`
list(
  body.completedOrders,
  body.currentOrder
)
\`\`\`
`,
      },
    },
    {
      label: "makeObject",
      snippet: "makeObject ( ${1:key}$0, ${2:value} )",
      documentation: {
        value: `
makeObject ( *key*, *value* )

#### Description

Creates new custom object from source objects and
primitives based on specified structure.

#### Parameters

* __key__ — path to source string field,
  which value will be taken as a new parameter name
  for a resulted object
* __value__ — path to source field, object or array,
  which value will be taken as a new parameter value
  for a resulted object

#### Example

To build new object with string key = 'name' and value from
"customer" source object, the following expression can be used:

\`\`\`
makeObject('name', body.customer)
\`\`\`
`,
      },
    },
    {
      label: "mergeObjects",
      snippet:
        "mergeObjects( ${1:source-object-or-array}$0, ${2:source-object-or-array})",
      documentation: {
        value: `
mergeObjects( *source-object-or-array*, *source-object-or-array* )

#### Description

Merges several source-object-or-array into new object.
It is possible to apply additional functions for source-object-or-array.

#### Parameters

* __source-object-or-array__ — path to the source object or array
  of objects to merge into a new object

#### Example

To merge source "customer" object and "orders" array of objects
into a new object, the following expression can be used:

\`\`\`
mergeObjects(
  body.customer,
  body.orders
)
\`\`\`
            `,
      },
    },
  ];

  private readonly PREDEFINED_CONSTANTS = ["null", "true", "false"];

  private referenceMap: Map<string, ExpressionReferences> = new Map<
    string,
    ExpressionReferences
  >();

  constructor() {
    // Do nothing
  }

  public getReferenceMap() {
    return this.referenceMap;
  }

  public provideCompletionItems(
    model: editor.ITextModel,
    position: Position,
  ): languages.ProviderResult<languages.CompletionList> {
    const references = this.getReferenceMap().get(model.uri.toString()) ?? {
      attributes: [],
      constants: [],
    };
    const line = model.getLineContent(position.lineNumber);
    const textBeforePosition = line.substring(0, position.column);
    const word = model.getWordUntilPosition(position);

    const startColumn =
      word.word === "" ||
      word.startColumn <= 1 ||
      " \r\n\t,(".includes(line[word.startColumn - 2])
        ? word.startColumn
        : this.getReferenceStartColumn(textBeforePosition);

    const range: IRange = {
      startLineNumber: position.lineNumber,
      endLineNumber: position.lineNumber,
      startColumn: startColumn,
      endColumn: word.endColumn,
    };

    const suggestions: languages.CompletionItem[] = [
      ...this.PREDEFINED_CONSTANTS.map((value) => ({
        label: value,
        kind: languages.CompletionItemKind.Constant,
        insertText: value,
        range,
      })),
      ...this.PREDEFINED_FUNCTIONS.map((fn) => ({
        label: fn.label,
        kind: languages.CompletionItemKind.Function,
        insertText: fn.snippet,
        insertTextRules: languages.CompletionItemInsertTextRule.InsertAsSnippet,
        documentation: fn.documentation,
        range,
      })),
      ...references.attributes
        .filter((detail) => detail.kind && detail.path.every((a) => a.name))
        .map((detail) => ({
          label: detail.kind + "." + detail.path.map((a) => a.name).join("."),
          kind: languages.CompletionItemKind.Variable,
          insertText: buildAttributeReferenceText(detail),
          range,
        })),
      ...references.constants
        .filter(constant => constant.name)
        .map(constant => ({
          label: buildConstantReferenceText(constant),
          kind: languages.CompletionItemKind.Constant,
          insertText: buildConstantReferenceText(constant),
          range
        }))
    ];

    return { suggestions: suggestions };
  }

  private getReferenceStartColumn(text: string): number {
    const index =
      ["body", "header", "property", "constant"]
        .map((word) => text.lastIndexOf(word))
        .sort((a, b) => a - b)
        .pop() ?? 0;
    return Math.max(0, index) + 1;
  }
}

const MAPPER_TRANSFORMATION_EXPRESSION_COMPLETION_PROVIDER =
  new TransformationExpressionCompletionProvider();

function configureMapperActionsLanguage(monaco: Monaco) {
  const alreadyRegistered = monaco.languages
    .getLanguages()
    .some(
      (language) =>
        language.id === MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID,
    );
  if (alreadyRegistered) {
    console.log(
      `Language already registered: ${MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID}`,
    );
    return;
  }
  monaco.languages.register({
    id: MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID,
  });
  monaco.languages.setLanguageConfiguration(
    MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID,
    MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_CONFIGURATION,
  );
  monaco.languages.setMonarchTokensProvider(
    MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID,
    MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_TOKENIZER,
  );
  monaco.languages.registerCompletionItemProvider(
    MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID,
    MAPPER_TRANSFORMATION_EXPRESSION_COMPLETION_PROVIDER,
  );
}

export type ExpressionEditorProps = {
  value?: string;
  onChange?: (value: string) => void;
};

export const ExpressionEditor: React.FC<ExpressionEditorProps> = ({
  value,
  onChange,
}) => {
  const { mappingDescription, action } = useContext(TransformationContext);
  const [references, setReferences] = useState<ExpressionReferences>({
    attributes: [],
    constants: [],
  });

  const editorRef = useRef<editor.IStandaloneCodeEditor>();
  const monacoRef = useRef<Monaco>();

  useEffect(() => {
    const uri = Uri.parse(action.id).toString();
    MAPPER_TRANSFORMATION_EXPRESSION_COMPLETION_PROVIDER.getReferenceMap().set(
      uri,
      references,
    );
    return () => {
      MAPPER_TRANSFORMATION_EXPRESSION_COMPLETION_PROVIDER.getReferenceMap().delete(
        uri,
      );
    };
  }, [action, references]);

  useEffect(() => {
    const attributes = action.sources
      .filter((source) => MappingUtil.isAttributeReference(source))
      .map((source) => {
        return MappingActions.resolveAttributeReference(
          source,
          mappingDescription.source,
        );
      });
    const constants = action.sources
      .filter((source) => MappingUtil.isConstantReference(source))
      .map((source) =>
        MappingUtil.findConstantById(mappingDescription, source.constantId),
      )
      .filter((constant) => !!constant);
    setReferences({ attributes, constants });
  }, [mappingDescription, action]);

  const validateValue = useCallback(
    (value: string) => {
      const model = editorRef.current?.getModel();
      if (!model) {
        return;
      }
      const expressionText = value.trim();
      if (!expressionText) {
        return;
      }
      const markers: editor.IMarkerData[] = [];
      validateExpression(
        expressionText,
        references.attributes,
        references.constants,
        (location, message) => {
          markers.push({
            severity: MarkerSeverity.Error,
            message,
            startLineNumber: location.start.line,
            startColumn: location.start.column,
            endLineNumber: location.end.line,
            endColumn: location.end.column,
          });
        },
      );

      monacoRef.current?.editor?.setModelMarkers(
        model,
        MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID,
        markers,
      );
    },
    [references.attributes, references.constants],
  );

  return (
    <Editor
      className="qip-editor"
      value={value}
      language={MAPPER_TRANSFORMATION_EXPRESSION_LANGUAGE_ID}
      path={action.id}
      onMount={(editor, monaco) => {
        editorRef.current = editor;
        monacoRef.current = monaco;
        configureMapperActionsLanguage(monaco);
      }}
      onChange={(value) => {
        const text = value ?? "";
        onChange?.(text);
        validateValue(text);
      }}
      options={{
        fixedOverflowWidgets: true,
      }}
    />
  );
};
