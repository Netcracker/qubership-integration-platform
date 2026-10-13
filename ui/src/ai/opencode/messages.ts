import type { Event, Message, Part } from "@opencode-ai/sdk/v2/client";

export type OpenCodeMessage = { id: string; info?: Message; parts: Part[] };

export function updateMessages(
  messages: OpenCodeMessage[],
  event: Event,
  sessionID: string,
): OpenCodeMessage[] {
  if (
    !("sessionID" in event.properties) ||
    event.properties.sessionID !== sessionID
  )
    return messages;
  switch (event.type) {
    case "message.updated": {
      const info = event.properties.info;
      const existing = messages.find((m) => m.id === info.id);
      return existing
        ? messages.map((m) => (m.id === info.id ? { ...m, info } : m))
        : [...messages, { id: info.id, info, parts: [] }];
    }
    case "message.removed":
      return messages.filter((m) => m.id !== event.properties.messageID);
    case "message.part.updated": {
      const part = event.properties.part;
      const existing = messages.find((m) => m.id === part.messageID);
      const entry = existing ?? { id: part.messageID, parts: [] };
      const parts = entry.parts.some((p) => p.id === part.id)
        ? entry.parts.map((p) => (p.id === part.id ? part : p))
        : [...entry.parts, part];
      return existing
        ? messages.map((m) => (m.id === entry.id ? { ...m, parts } : m))
        : [...messages, { ...entry, parts }];
    }
    case "message.part.delta":
      return messages.map((m) =>
        m.id !== event.properties.messageID
          ? m
          : {
              ...m,
              parts: m.parts.map((p) =>
                p.id === event.properties.partID &&
                (p.type === "text" || p.type === "reasoning") &&
                event.properties.field === "text"
                  ? { ...p, text: p.text + event.properties.delta }
                  : p,
              ),
            },
      );
    case "message.part.removed":
      return messages.map((m) =>
        m.id === event.properties.messageID
          ? {
              ...m,
              parts: m.parts.filter((p) => p.id !== event.properties.partID),
            }
          : m,
      );
    default:
      return messages;
  }
}
