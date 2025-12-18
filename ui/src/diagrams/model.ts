export type Participant = {
  id: string;
  name?: string;
};

export type ArrowType =
  | "arrow-solid"
  | "arrow-dotted"
  | "open-arrow-solid"
  | "open-arrow-dotted";

export type Message = {
  readonly type: "message";
  fromId: string;
  toId: string;
  arrowType: ArrowType;
  message?: string;
};

export type Loop = {
  readonly type: "loop";
  label: string;
  actions: Action[];
};

export type OptionalActions = {
  readonly type: "optional";
  label?: string;
  actions: Action[];
};

export type ActionGroup = {
  readonly type: "group";
  label?: string;
  actions: Action[];
};

export type Branch = {
  readonly type: "branch";
  label: string;
  actions: Action[];
};

export type Alternatives = {
  readonly type: "alternatives";
  branches: Branch[];
};

export type ParallelActions = {
  readonly type: "parallel";
  branches: Branch[];
};

export type Activate = {
  readonly type: "activate";
  participantId: string;
};

export type Deactivate = {
  readonly type: "deactivate";
  participantId: string;
};

export type CompositeAction =
  | ActionGroup
  | Loop
  | Alternatives
  | OptionalActions
  | ParallelActions;

export type SimpleAction = Activate | Deactivate | Message;
export type Action = SimpleAction | CompositeAction;

export type SequenceDiagram = {
  autonumber: boolean;
  title?: string;
  header?: string;
  footer?: string;
  participants: Participant[];
  actions: Action[];
};
