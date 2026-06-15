import { ChainGraphNodeData } from "../ChainGraphNodeTypes";
import { IfDescriptionFormatter } from "./IfDescriptionFormatter";
import { useLibraryContext } from "../../../LibraryContext";
import { findLibraryElement } from "../../../../misc/chain-graph-utils";
import { LibraryElement } from "../../../../index.bundled";
import { CatchDescriptionFormatter } from "./CatchDescriptionFormatter";

export type DescriptionFormatterProps = {
  data: ChainGraphNodeData;
  libraryElement?: LibraryElement;
};

const DESCRIPTION_FORMATTERS: Map<
  string,
  React.ComponentType<DescriptionFormatterProps>
> = new Map<string, React.ComponentType<DescriptionFormatterProps>>([
  ["catch-formatter", CatchDescriptionFormatter],
  ["if-formatter", IfDescriptionFormatter],
]);

export const DescriptionFormatter = (data: ChainGraphNodeData) => {
  const { libraryElements } = useLibraryContext();

  const libraryElement = findLibraryElement(data.elementType, libraryElements);
  const Component = libraryElement
    ? DESCRIPTION_FORMATTERS.get(libraryElement.descriptionFormatter)
    : undefined;

  return Component ? (
    <Component data={data} libraryElement={libraryElement} />
  ) : (
    <></>
  );
};
