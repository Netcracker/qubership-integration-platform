import { Chain } from "../../../api/apiTypes.ts";
import { Change } from "./compare/types.ts";
import React, { useEffect, useState } from "react";
import yaml, { DumpOptions } from "js-yaml";
import { DiffEditor } from "@monaco-editor/react";
import { useMonacoTheme } from "../../../hooks/useMonacoTheme.ts";
import { buildElementMap } from "./compare/compare.ts";
import styles from "./ChainDiffTextView.module.css";

export type ChainDiffTextViewProps = {
  chain1?: Chain;
  chain2?: Chain;
  changes: Change[];
  selectedChangeId?: string;
  onSelectChange: (id: string) => void;
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

export const ChainDiffTextView: React.FC<ChainDiffTextViewProps> = ({
  chain1,
  chain2,
}): React.ReactNode => {
  const [elementMap, setElementMap] = useState<Map<string, string>>(
    new Map<string, string>(),
  );
  const [yaml1, setYaml1] = useState<string>("");
  const [yaml2, setYaml2] = useState<string>("");
  const monacoTheme = useMonacoTheme();

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
    <div className={styles.textView}>
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
      />
    </div>
  );
};
