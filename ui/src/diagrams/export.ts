import { DiagramLangType } from "../api/apiTypes.ts";
import { SequenceDiagram } from "./model.ts";
import { exportAsMermaid } from "./mermaid.ts";
import { exportAsPlantUml } from "./plantuml.ts";

export type SequenceDiagramExporter = (diagram: SequenceDiagram) => string;

export function getSequenceDiagramExporter(
  language: DiagramLangType,
): SequenceDiagramExporter {
  switch (language) {
    case DiagramLangType.PLANT_UML:
      return (diagram) => exportAsPlantUml(diagram);
    case DiagramLangType.MERMAID:
      return (diagram) => exportAsMermaid(diagram);
  }
}
