export function traverseElementsDepthFirst<T extends { children?: T[] }>(
  elements: T[] | undefined,
  fn: (element: T, path: T[]) => void,
  path?: T[],
): void {
  const p = path ?? [];
  for (const element of elements ?? []) {
    fn(element, p);
    traverseElementsDepthFirst(element.children, fn, [...p, element]);
  }
}
