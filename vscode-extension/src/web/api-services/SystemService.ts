import { Uri } from "vscode";
import * as vscode from "vscode";
import { IntegrationSystem } from "./servicesTypes";
import { fileApi } from "../response/file/fileApiProvider";
import { getMainService } from "../response/serviceApiRead";
import { findServiceFileById } from "../response/file/serviceFileLookup";
import { resolveServiceType } from "../response/file/serviceFileType";
import { writeServiceInCurrentFormat } from "../response/file/serviceFileWrite";
import { LabelUtils } from "./LabelUtils";
import {
  getExtendedProtocol,
  getSpecificationType,
} from "../response/serviceApiUtils";

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
          type: type,
          protocol: service.content?.protocol || "",
          extendedProtocol: getExtendedProtocol(service.content?.protocol),
          specification: getSpecificationType(service.content?.protocol),
          labels: LabelUtils.toEntityLabels(service.content?.labels || []),
        };
      }
      console.log(`[SystemService] System with id ${systemId} not found`);
      return null;
    } catch (error) {
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
      console.error(
        `[SystemService] Error getting raw service ${systemId}:`,
        error,
      );
      return null;
    }
  }

  /**
   * Save system to file
   */
  async saveSystem(system: IntegrationSystem): Promise<void> {
    try {
      const serviceFileUri = await this.findServiceFileUri(system.id);

      const service = await fileApi.getMainService(serviceFileUri);

      if (!service.content) {
        service.content = {};
      }

      service.content.integrationSystemType =
        system.integrationSystemType || system.type;
      service.content.protocol = system.protocol
        ? system.protocol.toUpperCase()
        : system.protocol;
      service.content.labels = LabelUtils.fromEntityLabels(system.labels);

      await writeServiceInCurrentFormat(serviceFileUri, service);
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
