/** Ant Design overlays: anchor popups to table filter cell parent, else body. */
export function tableFilterPopupContainer(node: HTMLElement): HTMLElement {
  return node.parentElement ?? document.body;
}
