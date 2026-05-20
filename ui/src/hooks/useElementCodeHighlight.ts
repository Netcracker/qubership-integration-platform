import { useEffect, useRef } from "react";
import { editor, Range as MonacoRange } from "monaco-editor";
import { findElementBlockRanges } from "../misc/element-code-utils";

export const HIGHLIGHT_BLOCK_CLASS = "chain-text-view-highlighted-block";

export type UseElementCodeHighlightOptions = {
  editorRef: React.MutableRefObject<editor.IStandaloneCodeEditor | null>;
  selectedIds: readonly string[];
  content: string;
};

/**
 * Paints a left-stripe decoration for every selected element id and scrolls
 * only when a new id joins the selection — preserving the caret on content-
 * only updates.
 */
export const useElementCodeHighlight = ({
  editorRef,
  selectedIds,
  content,
}: UseElementCodeHighlightOptions): void => {
  const previousIdsRef = useRef<ReadonlySet<string>>(new Set());

  useEffect(() => {
    const editorInstance = editorRef.current;
    if (!editorInstance) return;

    const previousIds = previousIdsRef.current;
    const currentIds = new Set(selectedIds);
    previousIdsRef.current = currentIds;

    if (currentIds.size === 0) return;

    const model = editorInstance.getModel();
    if (!model) return;

    const ranges = findElementBlockRanges(model, selectedIds);
    if (ranges.size === 0) return;

    const decorations: editor.IModelDeltaDecoration[] = [];
    for (const [, range] of ranges) {
      decorations.push({
        range: new MonacoRange(
          range.startLine,
          1,
          range.endLine,
          model.getLineMaxColumn(range.endLine),
        ),
        options: { isWholeLine: true, className: HIGHLIGHT_BLOCK_CLASS },
      });
    }

    const collection = editorInstance.createDecorationsCollection(decorations);

    const newlyAdded = selectedIds.find(
      (id) => !previousIds.has(id) && ranges.has(id),
    );
    if (newlyAdded !== undefined) {
      const target = ranges.get(newlyAdded);
      if (target) {
        editorInstance.revealLineInCenterIfOutsideViewport(target.startLine);
      }
    }

    return () => {
      collection.clear();
    };
  }, [editorRef, selectedIds, content]);
};
