import { Uri } from "vscode";
import * as vscode from "vscode";
import { IntegrationSystem } from "./servicesTypes";
import { fileApi } from "../response/file/fileApiProvider";
import { getMainService } from "../response/serviceApiRead";
import { findServiceFileById } from "../response/file/serviceFileLookup";
import { UnreadableOutcomeError } from "../response/file/lookupOutcome";
import { resolveServiceType } from "../response/file/serviceFileType";
import { writeServiceInCurrentFormat } from "../response/file/serviceFileWrite";
import { LabelUtils } from "./LabelUtils";
import {
  getExtendedProtocol,
  getSpecificationType,
} from "../response/serviceApiUtils";

/**
 * A lookup that refused because it would have answered with the sibling of a file it could not read
 * is not "no such system": answering `null` here turns it into an unexplained absence, and the user
 * never learns which file to fix.
 */
function rethrowRefusal(error: unknown): void {
  if (error instanceof UnreadableOutcomeError) {
    throw error;
  }
}

/**
 * Service for managing integration systems
 * Provides functionality for reading and managing systems from files
 */
export class SystemService {
  constructor() {}

  /**
   * Get system by ID from service file
   */
  async getSystemById(systemId: string): Promise<IntegrationSystem | null> {
    try {
      const serviceFileUri = await this.findServiceFileUri(systemId);
      const service = await getMainService(serviceFileUri);
      if (service.id === systemId) {
        const type = resolveServiceType(serviceFileUri, service);
        return {
          id: service.id,
          name: service.name,
          description: service.content?.description || "",
          activeEnvironmentId: service.content?.activeEnvironmentId || "",
          integrationSystemType: type,
          type,
          protocol: service.content?.protocol || "",
          extendedProtocol: getExtendedProtocol(service.content?.protocol),
          specification: getSpecificationType(service.content?.protocol),
          labels: LabelUtils.toEntityLabels(service.content?.labels || []),
        };
      }
      console.log(`[SystemService] System with id ${systemId} not found`);
      return null;
    } catch (error) {
      rethrowRefusal(error);
      console.error(`[SystemService] Error getting system ${systemId}:`, error);
      return null;
    }
  }

  /**
   * Get raw service object by ID (with content structure)
   */
  async getRawServiceById(systemId: string): Promise<any | null> {
    try {
      const serviceFileUri = await this.findServiceFileUri(systemId);
      const service = await getMainService(serviceFileUri);
      if (service && service.id === systemId) {
        return service;
      }
      return null;
    } catch (error) {
      rethrowRefusal(error);
      console.error(
        `[SystemService] Error getting raw service ${systemId}:`,
        error,
      );
      return null;
    }
  }

  /**
   * Saves the system and returns the file it landed in — a conversion moves it out of the one it
   * was read from, and a caller holding the old uri reads a deleted path.
   *
   * The type is not written: it is set at creation and never again, and for a legacy file the value
   * already on disk is the one to keep.
   */
  async saveSystem(system: IntegrationSystem): Promise<Uri> {
    try {
      const serviceFileUri = await this.findServiceFileUri(system.id);

      const service = await fileApi.getMainService(serviceFileUri);

      if (!service.content) {
        service.content = {};
      }

      service.content.protocol = system.protocol
        ? system.protocol.toUpperCase()
        : system.protocol;
      service.content.labels = LabelUtils.fromEntityLabels(system.labels);

      return await writeServiceInCurrentFormat(serviceFileUri, service);
    } catch (error) {
      console.error(
        `[SystemService] Failed to save system ${system.id}:`,
        error,
      );
      throw error;
    }
  }

  private async findServiceFileUri(systemId: string): Promise<Uri> {
    return await findServiceFileById(systemId);
  }
}
