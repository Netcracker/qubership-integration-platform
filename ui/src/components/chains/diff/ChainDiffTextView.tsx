import { Chain } from "../../../api/apiTypes.ts";
import { Change } from "./compare/types.ts";
import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";
import yaml, { DumpOptions } from "js-yaml";
import { DiffEditor } from "@monaco-editor/react";
import type * as monacoNs from "monaco-editor";
import type { editor } from "monaco-editor";
import {
  applyVSCodeThemeToMonaco,
  useMonacoTheme,
} from "../../../hooks/useMonacoTheme.ts";
import { buildElementMap } from "./compare/compare.ts";

type Monaco = typeof monacoNs;
export type DiffNavigationDirection = "next" | "previous";

export type ChainDiffTextViewProps = {
  chain1?: Chain;
  chain2?: Chain;
  changes: Change[];
  selectedChangeId?: string;
  onSelectChange: (id: string) => void;
};

export type ChainDiffTextViewHandle = {
  goToDiff: (direction: DiffNavigationDirection) => void;
};

const IGNORED_PROPERTIES = new Set<string>([
  "id",
  "parentId",
  "swimlaneId",
  "deployments",
  "createdBy",
  "createdWhen",
  "modifiedWhen",
  "modifiedBy",
  "chainId",
  "mandatoryChecksPassed",
  "navigationPath",
  "containsDeprecatedContainers",
  "containsDeprecatedElements",
  "containsUnsupportedElements",
  "unsavedChanges",
]);

export function dumpYaml(chain: Chain, m: Map<string, string>): string {
  const options: DumpOptions = {
    indent: 2,
    noArrayIndent: true,
    skipInvalid: true,
    sortKeys: true,
    replacer: (key, value: unknown) => {
      if (IGNORED_PROPERTIES.has(key)) {
        return undefined;
      }
      if (key === "from" || key === "to") {
        const element = chain.elements.find((e) => e.id === value);
        return element ? `${element.name} (${element.type})` : value;
      }

      if (key === "elements" && Array.isArray(value)) {
        return value.sort((v1, v2) => {
          // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment,@typescript-eslint/no-unsafe-argument,@typescript-eslint/no-unsafe-member-access
          const id1 = m.get(v1?.id) ?? v1?.id;
          // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment,@typescript-eslint/no-unsafe-argument,@typescript-eslint/no-unsafe-member-access
          const id2 = m.get(v2?.id) ?? v2?.id;
          return `${id1}`.localeCompare(`${id2}`);
        }) as unknown[];
      }

      return value;
    },
  };
  return yaml.dump(chain, options);
}

export const ChainDiffTextView = forwardRef<
  ChainDiffTextViewHandle,
  ChainDiffTextViewProps
>(({ chain1, chain2 }, ref): React.ReactNode => {
  const [elementMap, setElementMap] = useState<Map<string, string>>(
    new Map<string, string>(),
  );
  const [yaml1, setYaml1] = useState<string>("");
  const [yaml2, setYaml2] = useState<string>("");
  const monacoTheme = useMonacoTheme();
  const editorRef = useRef<editor.IStandaloneDiffEditor | null>(null);

  useImperativeHandle(
    ref,
    () => ({
      goToDiff: (direction) => editorRef.current?.goToDiff(direction),
    }),
    [],
  );

  const handleMount = useCallback(
    (editorInstance: editor.IStandaloneDiffEditor, monaco: Monaco) => {
      editorRef.current = editorInstance;
      applyVSCodeThemeToMonaco(monaco);
    },
    [],
  );

  useEffect(() => {
    setYaml1(chain1 ? dumpYaml(chain1, new Map<string, string>()) : "");
  }, [chain1]);

  useEffect(() => {
    setYaml2(chain2 ? dumpYaml(chain2, elementMap) : "");
  }, [chain2, elementMap]);

  useEffect(() => {
    setElementMap(
      chain1 && chain2
        ? buildElementMap(chain1, chain2)
        : new Map<string, string>(),
    );
  }, [chain1, chain2]);

  return (
    <DiffEditor
      className="qip-editor"
      originalLanguage={"yaml"}
      modifiedLanguage={"yaml"}
      original={yaml1}
      modified={yaml2}
      theme={monacoTheme}
      options={{
        readOnly: true,
        originalAriaLabel: "Body Before",
        modifiedAriaLabel: "Body After",
        automaticLayout: true,
      }}
      onMount={handleMount}
    />
  );
});

ChainDiffTextView.displayName = "ChainDiffTextView";
