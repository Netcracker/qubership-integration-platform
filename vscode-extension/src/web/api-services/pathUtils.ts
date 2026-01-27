export function normalizePath(value: string): string {
  return value
    .replace(/\\/g, "/")
    .split("/")
    .reduce<string[]>((segments, segment) => {
      if (!segment || segment === ".") {
        return segments;
      }
      if (segment === "..") {
        segments.pop();
      } else {
        segments.push(segment);
      }
      return segments;
    }, [])
    .join("/");
}
