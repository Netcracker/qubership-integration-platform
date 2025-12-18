import {
  Chain,
  DiagramLangType,
  DiagramMode,
  ElementsSequenceDiagram,
  ElementsSequenceDiagrams,
} from "../api/apiTypes.ts";
import { buildSequenceDiagram } from "./builder.ts";
import { getSequenceDiagramExporter } from "./export.ts";

export async function generateSequenceDiagrams(
  chain: Chain,
): Promise<ElementsSequenceDiagrams> {
  const result: ElementsSequenceDiagrams = Object.fromEntries(
    await Promise.all(
      [DiagramMode.FULL, DiagramMode.SIMPLE].map(async (mode) => {
        const diagram = await buildSequenceDiagram(chain, mode);
        const diagramItem: ElementsSequenceDiagram = {
          chainId: chain.id,
          diagramSources: Object.fromEntries(
            [DiagramLangType.PLANT_UML, DiagramLangType.MERMAID].map((lang) => {
              const exporter = getSequenceDiagramExporter(lang);
              return [lang, exporter(diagram)];
            }),
          ) as ElementsSequenceDiagram["diagramSources"],
        };
        return [mode, diagramItem];
      }),
    ),
  ) as ElementsSequenceDiagrams;
  return Promise.resolve(result);
}
