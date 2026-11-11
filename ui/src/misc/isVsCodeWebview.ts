// VS Code defines acquireVsCodeApi in every webview before the bundle runs. The protocol differs
// by host: vscode-webview: on desktop, http(s): in the web workbench.
export function isVsCodeWebview(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof (window as { acquireVsCodeApi?: unknown }).acquireVsCodeApi ===
      "function"
  );
}
