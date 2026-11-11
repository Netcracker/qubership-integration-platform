/** `text` without its ANSI color codes. */
export function withoutColors(text: string): string {
  return text.replace(/\u001b\[[0-9;]*m/g, "");
}
