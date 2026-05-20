/**
 * @jest-environment jsdom
 */

import {
  submitAddInstruction,
  uploadImportInstructionsFile,
} from "../../../src/components/admin_tools/importInstructionsHandlers";
import {
  ImportInstructionAction,
  ImportEntityType,
  ImportInstructionStatus,
} from "../../../src/api/apiTypes";

describe("submitAddInstruction", () => {
  it("calls API and onSuccess when form validates and API succeeds", async () => {
    const form = {
      validateFields: jest.fn().mockResolvedValue({
        id: " my-chain-id ",
        entityType: "Chain" as const,
        action: ImportInstructionAction.IGNORE,
      }),
    };
    const api = {
      addImportInstruction: jest.fn().mockResolvedValue(undefined),
    };
    const notificationService = { requestFailed: jest.fn() };
    const onSuccess = jest.fn();

    await submitAddInstruction(form, api, notificationService, onSuccess);

    expect(form.validateFields).toHaveBeenCalledTimes(1);
    expect(api.addImportInstruction).toHaveBeenCalledWith({
      id: "my-chain-id",
      entityType: ImportEntityType.CHAIN,
      action: ImportInstructionAction.IGNORE,
      overriddenBy: undefined,
    });
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(notificationService.requestFailed).not.toHaveBeenCalled();
  });

  it("includes overriddenBy when entityType is Chain and action is OVERRIDE", async () => {
    const form = {
      validateFields: jest.fn().mockResolvedValue({
        id: "chain-1",
        entityType: "Chain" as const,
        action: ImportInstructionAction.OVERRIDE,
        overriddenBy: " other-chain ",
      }),
    };
    const api = {
      addImportInstruction: jest.fn().mockResolvedValue(undefined),
    };
    const notificationService = { requestFailed: jest.fn() };
    const onSuccess = jest.fn();

    await submitAddInstruction(form, api, notificationService, onSuccess);

    expect(api.addImportInstruction).toHaveBeenCalledWith({
      id: "chain-1",
      entityType: ImportEntityType.CHAIN,
      action: ImportInstructionAction.OVERRIDE,
      overriddenBy: "other-chain",
    });
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it("calls requestFailed when API rejects", async () => {
    const form = {
      validateFields: jest.fn().mockResolvedValue({
        id: "some-id",
        entityType: "Service" as const,
        action: ImportInstructionAction.IGNORE,
      }),
    };
    const api = {
      addImportInstruction: jest
        .fn()
        .mockRejectedValue(new Error("server error")),
    };
    const notificationService = { requestFailed: jest.fn() };
    const onSuccess = jest.fn();

    await submitAddInstruction(form, api, notificationService, onSuccess);

    expect(api.addImportInstruction).toHaveBeenCalled();
    expect(notificationService.requestFailed).toHaveBeenCalledWith(
      "Failed to add import instruction",
      expect.any(Error),
    );
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it("does not call requestFailed when validation fails with errorFields", async () => {
    const validationError = Object.assign(new Error("Validation failed"), {
      errorFields: [{ name: ["id"], errors: ["Id is required"] }],
    });
    const form = {
      validateFields: jest.fn().mockRejectedValue(validationError),
    };
    const api = { addImportInstruction: jest.fn() };
    const notificationService = { requestFailed: jest.fn() };
    const onSuccess = jest.fn();

    await submitAddInstruction(form, api, notificationService, onSuccess);

    expect(api.addImportInstruction).not.toHaveBeenCalled();
    expect(notificationService.requestFailed).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });
});

describe("uploadImportInstructionsFile", () => {
  it("calls API and onSuccess when fileList has file and API succeeds", async () => {
    const file = new File(["yaml"], "test.yaml", { type: "text/yaml" });
    const fileList = [{ uid: "1", originFileObj: file }];
    const results = [
      {
        id: "c1",
        name: "Chain 1",
        entityType: ImportEntityType.CHAIN,
        status: ImportInstructionStatus.NO_ACTION,
        errorMessage: "",
      },
    ];
    const api = {
      uploadImportInstructions: jest.fn().mockResolvedValue(results),
    };
    const notificationService = { requestFailed: jest.fn() };
    const onSuccess = jest.fn();

    await uploadImportInstructionsFile(
      fileList,
      api,
      notificationService,
      onSuccess,
    );

    expect(api.uploadImportInstructions).toHaveBeenCalledWith(file);
    expect(onSuccess).toHaveBeenCalledWith(results);
    expect(notificationService.requestFailed).not.toHaveBeenCalled();
  });

  it("calls requestFailed when API rejects", async () => {
    const file = new File(["yaml"], "test.yaml", { type: "text/yaml" });
    const fileList = [{ uid: "1", originFileObj: file }];
    const api = {
      uploadImportInstructions: jest
        .fn()
        .mockRejectedValue(new Error("upload failed")),
    };
    const notificationService = { requestFailed: jest.fn() };
    const onSuccess = jest.fn();

    await uploadImportInstructionsFile(
      fileList,
      api,
      notificationService,
      onSuccess,
    );

    expect(api.uploadImportInstructions).toHaveBeenCalled();
    expect(notificationService.requestFailed).toHaveBeenCalledWith(
      "Failed to upload import instructions",
      expect.any(Error),
    );
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it("does nothing when fileList is empty", async () => {
    const api = { uploadImportInstructions: jest.fn() };
    const notificationService = { requestFailed: jest.fn() };
    const onSuccess = jest.fn();

    await uploadImportInstructionsFile([], api, notificationService, onSuccess);

    expect(api.uploadImportInstructions).not.toHaveBeenCalled();
    expect(notificationService.requestFailed).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it("does nothing when first file has no originFileObj", async () => {
    const fileList = [{ uid: "1" }];
    const api = { uploadImportInstructions: jest.fn() };
    const notificationService = { requestFailed: jest.fn() };
    const onSuccess = jest.fn();

    await uploadImportInstructionsFile(
      fileList,
      api,
      notificationService,
      onSuccess,
    );

    expect(api.uploadImportInstructions).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });
});
