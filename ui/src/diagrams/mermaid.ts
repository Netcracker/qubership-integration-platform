import {
  Action,
  Branch,
  ArrowType,
  Participant,
  SequenceDiagram,
} from "./model.ts";

export function exportAsMermaid(diagram: SequenceDiagram): string {
  const lines: string[] = [];
  lines.push("sequenceDiagram;");

  if (diagram.autonumber) {
    lines.push("autonumber;");
  }

  diagram.participants
    .map((participant) => exportParticipant(participant))
    .forEach((line) => lines.push(line));

  diagram.actions
    .map((action) =>
      exportAction(action, () => diagram.participants[0]?.id ?? ""),
    )
    .forEach((actionLines) => lines.push(...actionLines));

  return lines.join("\n");
}

function exportParticipant(participant: Participant): string {
  return `participant ${_escape(participant.id)} as ${_escape(participant.name ?? participant.id)};`;
}

function exportAction(
  action: Action,
  participantIdGetter: (action: Action) => string,
): string[] {
  switch (action.type) {
    case "activate":
      return [`activate ${_escape(action.participantId)};`];
    case "deactivate":
      return [`deactivate ${_escape(action.participantId)};`];
    case "message":
      return [
        `${_escape(action.fromId)} ${getArrow(action.arrowType)} ${_escape(action.toId)}${action.message ? ` : ${_escape(action.message)}` : ""};`,
      ];
    case "loop":
      return exportGroup(
        "loop",
        action.label,
        action.actions,
        participantIdGetter,
      );
    case "group":
      return exportRect(
        participantIdGetter(action),
        action.label,
        action.actions,
        participantIdGetter,
      );
    case "optional":
      return exportGroup(
        "opt",
        action.label,
        action.actions,
        participantIdGetter,
      );
    case "alternatives":
      return exportMultiGroup(
        "alt",
        "else",
        action.branches,
        participantIdGetter,
      );
    case "parallel":
      return exportMultiGroup(
        "par",
        "and",
        action.branches,
        participantIdGetter,
      );
  }
}

function exportRect(
  participantId: string,
  label: string | undefined,
  actions: Action[],
  participantIdGetter: (action: Action) => string,
): string[] {
  return [
    "rect rgb(250, 250, 250);",
    `note right of ${_escape(participantId)} : ${_escape(label ?? "")};`,
    ...exportActions(actions, participantIdGetter),
    "end;",
  ];
}

function exportGroup(
  tag: string,
  label: string | undefined,
  actions: Action[],
  participantIdGetter: (action: Action) => string,
): string[] {
  return [
    `${tag} ${_escape(label ?? "")};`,
    ...exportActions(actions, participantIdGetter),
    "end;",
  ];
}

function exportMultiGroup(
  tag: string,
  branchTag: string,
  branches: Branch[],
  participantIdGetter: (action: Action) => string,
): string[] {
  return branches.length === 0
    ? []
    : [
        `${tag} ${_escape(branches[0].label)};`,
        ...exportActions(branches[0].actions, participantIdGetter),
        ...branches
          .slice(1)
          .flatMap((branch) => [
            `${branchTag} ${_escape(branch.label)};`,
            ...exportActions(branch.actions, participantIdGetter),
          ]),
        "end;",
      ];
}

function exportActions(
  actions: Action[],
  participantIdGetter: (action: Action) => string,
): string[] {
  return actions.flatMap((a) => exportAction(a, participantIdGetter));
}

function getArrow(arrowType: ArrowType): string {
  switch (arrowType) {
    case "arrow-solid":
      return "->>";
    case "arrow-dotted":
      return "-->>";
    case "open-arrow-solid":
      return "-)";
    case "open-arrow-dotted":
      return "--)";
  }
}

function mustBeEscaped(c: string): boolean {
  const code = c.charCodeAt(0);
  return "#<>,-:;%".includes(c) || code > 126 || code < 32;
}

function _escape(text: string): string {
  return text
    .split("")
    .map((c) => (mustBeEscaped(c) ? `#${c.charCodeAt(0)};` : c))
    .join("");
}
