import {
  ImportInstructionAction,
  ImportInstructionResult,
  ImportEntityType,
} from "../../api/apiTypes.ts";

export type InstructionEntityType = "Chain" | "Service" | "Common Variable";

const ENTITY_TO_API: Record<InstructionEntityType, ImportEntityType> = {
  Chain: ImportEntityType.CHAIN,
  Service: ImportEntityType.SERVICE,
  "Common Variable": ImportEntityType.COMMON_VARIABLE,
};

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
  addImportInstruction: (params: unknown) => Promise<unknown>;
};

export type NotificationService = {
  requestFailed: (msg: string, err: unknown) => void;
};

/**
 * Validates form, calls API, invokes onSuccess or requestFailed.
 * Extracted for synchronous unit testing without UI.
 */
export async function submitAddInstruction(
  form: AddInstructionFormLike,
  api: AddInstructionApi,
  notificationService: NotificationService,
  onSuccess: () => void,
): Promise<void> {
  try {
    const values = await form.validateFields();
    await api.addImportInstruction({
      id: values.id.trim(),
      entityType: ENTITY_TO_API[values.entityType],
      action: values.action,
      overriddenBy:
        values.entityType === "Chain" &&
        values.action === ImportInstructionAction.OVERRIDE
          ? (values.overriddenBy?.trim() ?? null)
          : undefined,
    });
    onSuccess();
  } catch (err: unknown) {
    if (err && typeof err === "object" && "errorFields" in err) return;
    notificationService.requestFailed("Failed to add import instruction", err);
  }
}

export type UploadFileLike = {
  originFileObj?: File;
};

export type UploadApi = {
  uploadImportInstructions: (file: File) => Promise<ImportInstructionResult[]>;
};

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
  const file = fileList[0];
  const rawFile =
    file && "originFileObj" in file ? file.originFileObj : undefined;
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
