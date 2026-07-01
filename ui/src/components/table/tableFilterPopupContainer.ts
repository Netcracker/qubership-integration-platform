/** Anchor nested Ant Design popups to the outer dropdown, or document.body if none exists. */
export function tableFilterPopupContainer(node: HTMLElement): HTMLElement {
  return node.closest<HTMLElement>(".ant-dropdown") ?? document.body;
}
