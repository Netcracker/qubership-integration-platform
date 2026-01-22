import {
  Action,
  Branch,
  ArrowType,
  Participant,
  SequenceDiagram,
} from "./model.ts";

export function exportAsPlantUml(diagram: SequenceDiagram): string {
  const lines: string[] = [];
  lines.push("@startuml");

  if (diagram.header) {
    lines.push(`header "${_escape(diagram.header)}"`);
  }

  if (diagram.footer) {
    lines.push(`footer "${_escape(diagram.footer)}"`);
  }

  if (diagram.title) {
    lines.push(`title "${_escape(diagram.title)}"`);
  }

  if (diagram.autonumber) {
    lines.push("autonumber");
  }

  diagram.participants
    .map((participant) => exportParticipant(participant))
    .forEach((line) => lines.push(line));

  diagram.actions
    .map((action) => exportAction(action))
    .forEach((actionLines) => lines.push(...actionLines));

  lines.push("@enduml");
  return lines.join("\n");
}

function exportParticipant(participant: Participant): string {
  return `participant "${_escape(participant.name ?? participant.id)}" as ${escapeId(participant.id)}`;
}

function exportAction(action: Action): string[] {
  switch (action.type) {
    case "activate":
      return [`activate ${escapeId(action.participantId)}`];
    case "deactivate":
      return [`deactivate ${escapeId(action.participantId)}`];
    case "message":
      return [
        `${escapeId(action.fromId)} ${getArrow(action.arrowType)} ${escapeId(action.toId)}${action.message ? ` : "${_escape(action.message)}"` : ""}`,
      ];
    case "loop":
      return exportGroup("loop", action.label, action.actions);
    case "group":
      return exportGroup("group", action.label, action.actions);
    case "optional":
      return exportGroup("opt", action.label, action.actions);
    case "alternatives":
      return exportMultiGroup("alt", action.branches);
    case "parallel":
      return exportMultiGroup("par", action.branches);
  }
}

function exportMultiGroup(tag: string, branches: Branch[]): string[] {
  return branches.length === 0
    ? []
    : [
        `${tag} "${_escape(branches[0].label)}"`,
        ...exportActions(branches[0].actions),
        ...branches
          .slice(1)
          .flatMap((branch) => [
            `else "${_escape(branch.label)}"`,
            ...exportActions(branch.actions),
          ]),
        "end",
      ];
}

function exportGroup(
  tag: string,
  label: string | undefined,
  actions: Action[],
): string[] {
  return [`${tag} ${_escape(label ?? "")}`, ...exportActions(actions), "end"];
}

function exportActions(actions: Action[]): string[] {
  return actions.flatMap((a) => exportAction(a));
}

function getArrow(arrowType: ArrowType): string {
  switch (arrowType) {
    case "arrow-solid":
      return "->";
    case "arrow-dotted":
      return "-->";
    case "open-arrow-solid":
      return "->>";
    case "open-arrow-dotted":
      return "-->>";
  }
}

function _escape(text: string): string {
  const substitutionMap = new Map<string, string>([
    ['"', "<U+0022>"],
    ["\n", "\\n"],
    ["\r", "\\r"],
    ["\t", "\\t"],
  ]);
  return text
    .split("")
    .map((c) => substitutionMap.get(c) ?? c)
    .join("");
}

function escapeId(text: string): string {
  return replaceUnsupportedCharacters(text);
}

function isAllowedInId(c: string): boolean {
  return /[_0-9a-zA-Z]/.test(c);
}

function replaceUnsupportedCharacter(c: string): string {
  return `_${c.charCodeAt(0)}_`;
}

function replaceUnsupportedCharacters(text: string) {
  return text
    .split("")
    .map((c) => (isAllowedInId(c) ? c : replaceUnsupportedCharacter(c)))
    .join("");
}
