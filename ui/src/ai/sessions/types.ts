import { ChatMessage } from "../modelProviders/types.ts";

export type ChatMode = "ask" | "agent";

export type ElementCreationStatus =
  | "planned"
  | "creating"
  | "created"
  | "verified"
  | "failed"
  | "updated";

export interface PlannedElement {
  id: string;
  type: string;
  name: string;
  order: number;
  properties: Record<string, unknown>;
  status: ElementCreationStatus;
  elementId?: string;
  error?: string;
  verifiedAt?: number;
}

export interface PlannedConnection {
  id: string;
  fromElementId: string;
  toElementId: string;
  status: "planned" | "created" | "failed";
  error?: string;
}

export interface ChainCreationPlan {
  chainId: string;
  elements: PlannedElement[];
  connections: PlannedConnection[];
  currentStep?: number;
  status: "planning" | "creating" | "connecting" | "completed" | "failed";
  createdAt: number;
  updatedAt: number;
}

export interface ChatSession {
  id: string;
  title: string;
  messages: ChatMessage[];
  mode: ChatMode;
  createdAt: number;
  updatedAt: number;
  chainCreationPlan?: ChainCreationPlan;
  /** MinIO attachment URLs from the last user message that had attachments. */
  lastAttachmentUrls?: string[];
  /** S3 object keys merged across sends so follow-ups keep access without re-picking the file. */
  lastAttachmentObjectKeys?: string[];
  /** Server-side conversation ID for lightweight mode. */
  conversationId?: string;
}
