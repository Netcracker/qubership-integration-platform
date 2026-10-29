import {
  ImportInstructionAction,
  ImportInstructionResult,
  ImportInstructionRequest,
  ImportInstruction,
} from "../../api/apiTypes.ts";
import yaml from "js-yaml";

export type InstructionEntityType = "Chain" | "Service" | "Common Variable";

export type AddInstructionFormValues = {
  id: string;
  entityType: InstructionEntityType;
  action: ImportInstructionAction;
  overriddenBy?: string;
};

export type AddInstructionFormLike = {
  validateFields: () => Promise<AddInstructionFormValues>;
};

export type AddInstructionApi = {
  addImportInstruction: (
    request: ImportInstructionRequest,
  ) => Promise<void | ImportInstruction>;
};

export type NotificationService = {
  requestFailed: (msg: string, err: unknown) => void;
};

export type UploadFileLike = {
  originFileObj?: File;
};

export type UploadApi = {
  uploadImportInstructions: (file: File) => Promise<ImportInstructionResult[]>;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function hasNonEmptyInstructionList(value: unknown): boolean {
  if (Array.isArray(value)) return value.length > 0;
  if (isRecord(value)) return Object.keys(value).length > 0;
  if (typeof value === "string") return value.trim().length > 0;
  return value != null && value !== false;
}

export function hasDeleteInstructions(instructions: unknown): boolean {
  if (!isRecord(instructions)) return false;

  return Object.values(instructions).some((section) => {
    if (!isRecord(section)) return false;
    return hasNonEmptyInstructionList(section.delete);
  });
}

function getUploadFile(fileList: UploadFileLike[]): File | undefined {
  const file = fileList[0];
  return file && "originFileObj" in file ? file.originFileObj : undefined;
}

function readUploadFileText(file: File): Promise<string> {
  if (typeof file.text === "function") {
    return file.text();
  }

  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === "string") {
        resolve(reader.result);
        return;
      }
      reject(new Error("Failed to read upload file as text"));
    };
    reader.onerror = () => {
      reject(reader.error ?? new Error("Failed to read upload file"));
    };
    reader.readAsText(file);
  });
}

export async function uploadFileHasDeleteInstructions(
  fileList: UploadFileLike[],
): Promise<boolean> {
  const rawFile = getUploadFile(fileList);
  if (!rawFile) return false;

  try {
    const instructions = yaml.load(await readUploadFileText(rawFile));
    return hasDeleteInstructions(instructions);
  } catch {
    return false;
  }
}

/**
 * Uploads file from fileList, invokes onSuccess with results or requestFailed on error.
 * Extracted for synchronous unit testing without UI.
 */
export async function uploadImportInstructionsFile(
  fileList: UploadFileLike[],
  api: UploadApi,
  notificationService: NotificationService,
  onSuccess: (results: ImportInstructionResult[]) => void,
): Promise<void> {
  const rawFile = getUploadFile(fileList);
  if (!rawFile) return;
  try {
    const results = await api.uploadImportInstructions(rawFile);
    onSuccess(results);
  } catch (err: unknown) {
    notificationService.requestFailed(
      "Failed to upload import instructions",
      err,
    );
  }
}
