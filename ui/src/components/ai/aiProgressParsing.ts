const PROGRESS_LINE_REGEX = /^>\s*💡\s*(.+)$/gm;
const COMPLETED_SUFFIX = / - completed/i;
const ERROR_SUFFIX = / - error:\s*/i;

export type ProgressLineStatus = "success" | "error" | "pending";

export interface ParsedProgressLine {
  text: string;
  status: ProgressLineStatus;
}

export function parseProgressLines(content: string): ParsedProgressLine[] {
  const re = new RegExp(PROGRESS_LINE_REGEX.source, "gm");
  const lines: ParsedProgressLine[] = [];
  let match: RegExpExecArray | null;
  while ((match = re.exec(content)) !== null) {
    const text = match[1].trim();
    let status: ProgressLineStatus = "pending";
    if (COMPLETED_SUFFIX.test(text)) status = "success";
    else if (ERROR_SUFFIX.test(text)) status = "error";
    lines.push({ text, status });
  }
  return lines;
}

export function collapseProgressLines(
  lines: ParsedProgressLine[],
): ParsedProgressLine[] {
  if (lines.length === 0) return [];
  const completed = lines.filter(
    (l) => l.status === "success" || l.status === "error",
  );
  const lastLine = lines[lines.length - 1];
  const lastCompletedIndex =
    lines
      .map((l, i) => (l.status === "success" || l.status === "error" ? i : -1))
      .filter((i) => i >= 0)
      .pop() ?? -1;
  const pendingAfterLastCompleted = lines
    .slice(lastCompletedIndex + 1)
    .filter((l) => l.status === "pending");
  const lastRealPending =
    pendingAfterLastCompleted.length > 0
      ? pendingAfterLastCompleted[pendingAfterLastCompleted.length - 1]
      : null;
  const currentStep =
    lastLine?.status === "pending" ? lastLine : lastRealPending;
  return [...completed, ...(currentStep ? [currentStep] : [])];
}

export function stripProgressBlocks(content: string): string {
  return content
    .replace(/(\n\n>\s*💡\s*[^\n]+)+/g, "\n\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export function stripInlineProgressSummary(content: string): string {
  return content
    .split("\n")
    .filter((line) => {
      const t = line.trim();
      if (!t) return true;
      return !/^(💡[^💡]*?- completed\s*)+$/u.test(t);
    })
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}
