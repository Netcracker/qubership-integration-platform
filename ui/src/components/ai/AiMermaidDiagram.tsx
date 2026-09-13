import { useEffect, useId, useState } from "react";
import DOMPurify from "dompurify";
import { useVSCodeTheme } from "../../hooks/useVSCodeTheme.ts";

export function AiMermaidDiagram({ code }: { code: string }) {
  const id = useId().replace(/:/g, "");
  const { isDark } = useVSCodeTheme();
  const [result, setResult] = useState<{
    code: string;
    isDark: boolean;
    svg: string;
  }>();

  useEffect(() => {
    let cancelled = false;
    const container = document.createElement("div");
    // Wait for streamed text to settle before parsing the diagram.
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const { default: mermaid } = await import("mermaid");
          if (cancelled) return;
          mermaid.initialize({
            startOnLoad: false,
            securityLevel: "strict",
            suppressErrorRendering: true,
            theme: isDark ? "dark" : "default",
          });
          container.style.visibility = "hidden";
          container.style.position = "absolute";
          document.body.append(container);
          const { svg } = await mermaid.render(
            `ai-mermaid-${id}`,
            code,
            container,
          );
          if (!cancelled) {
            setResult({ code, isDark, svg: DOMPurify.sanitize(svg) });
          }
        } catch {
          // Keep the source visible for incomplete or invalid diagrams.
          if (!cancelled) setResult(undefined);
        } finally {
          container.remove();
        }
      })();
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      container.remove();
    };
  }, [code, id, isDark]);

  if (!result?.svg || result.code !== code || result.isDark !== isDark) {
    return <pre>{code}</pre>;
  }

  return (
    <div
      className="ai-mermaid-diagram"
      role="img"
      aria-label="Mermaid diagram"
      // eslint-disable-next-line react/no-danger
      dangerouslySetInnerHTML={{ __html: result.svg }}
    />
  );
}
