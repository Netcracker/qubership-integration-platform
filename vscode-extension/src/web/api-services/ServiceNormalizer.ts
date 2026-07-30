import {
  repairMigrationsClaim,
  SERVICE_MIGRATIONS,
} from "../services/importMigrationVersions";

/**
 * Fills in a service object read from disk with the two values the write path
 * cannot reconstruct on its own: the migrations claim the backend import
 * requires, and the environment `sourceType` the service schema marks required.
 *
 * Blank strings and empty collections are deliberately left out. Every reader
 * defaults them itself — `getService` and `parseEnvironment` in serviceApiRead,
 * the update paths in serviceApiModify and EnvironmentService — and
 * `writeServiceFile` prunes empty values on the way out, so filling them in
 * only produced placeholders that the write boundary deleted again.
 */
export class ServiceNormalizer {
  static normalizeService(service: any): any {
    if (!service) {
      return service;
    }

    if (!service.content || typeof service.content !== "object") {
      service.content = {};
    }
    repairMigrationsClaim(service.content, SERVICE_MIGRATIONS);

    if (Array.isArray(service.content.environments)) {
      for (const environment of service.content.environments) {
        if (environment && environment.sourceType === undefined) {
          environment.sourceType = "MANUAL";
        }
      }
    }

    return service;
  }
}
