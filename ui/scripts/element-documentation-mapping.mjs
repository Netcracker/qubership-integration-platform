export const ELEMENT_ALIASES = {
  condition: ["else", "if"],
  "try-catch-finally": [
    "try",
    "catch",
    "finally",
    "try-2",
    "catch-2",
    "finally-2",
    "try-catch-finally-2",
  ],
  "headers-modification": ["header-modification"],
  "asyncapi-trigger": ["async-api-trigger"],
  scheduler: ["quartz", "quartz-scheduler"],
  "chain-call": ["chain-call-2"],
  "chain-trigger": ["chain-trigger-2"],
  "circuit-breaker": [
    "circuit-breaker-2",
    "circuit-breaker-configuration-2",
    "on-fallback-2",
  ],
  loop: ["loop-2"],
  split: ["split-2", "split-element-2", "main-split-element-2"],
  "split-async": ["split-async-2", "async-split-element-2"],
  "kafka-sender": ["kafka-sender-2"],
  "kafka-trigger": ["kafka-trigger-2"],
  "rabbitmq-sender": ["rabbitmq-sender-2"],
  "rabbitmq-trigger": ["rabbitmq-trigger-2"],
  mapper: ["mapper-2"],
  "sftp-trigger": ["sftp-trigger-2"],
};

export function buildElementTypeMapping(paths) {
  const mapping = {};
  for (const docPath of paths) {
    const parts = docPath.split("/");
    if (!parts.some((part) => /(^|__)(QIP_)?Elements_Library$/.test(part)))
      continue;
    const file = parts.at(-1).replace(".md", "");
    const folder = parts.at(-2);
    const types = new Set();
    if (file && file !== "index" && file !== "readme")
      types.add(file.replace(/_/g, "-"));
    const folderPart = folder.split("__")[1];
    if (folderPart) types.add(folderPart.replace(/_/g, "-").toLowerCase());
    const url = `/doc/${docPath.replace(".md", "")}`;
    for (const type of types) mapping[type] ??= url;
  }
  for (const [type, aliases] of Object.entries(ELEMENT_ALIASES)) {
    if (mapping[type])
      for (const alias of aliases) mapping[alias] = mapping[type];
  }
  return mapping;
}
