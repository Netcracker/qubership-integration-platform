import { Uri } from "vscode";
import { ProjectConfigService } from "./ProjectConfigService";
import { extractFilename } from "../response/file/fileExtensions";

interface CacheEntry {
  uri: Uri;
  timestamp: number;
}

// A group file lives under either `.api-group.<app>.yaml` or the pre-rename `.specification-group.<app>.yaml`,
// and both share one cache. `endsWith` matches a filename, `equals` an extension passed in by the caller.
function isGroupExtension(
  config: { extensions: { specificationGroup: string; apiGroup: string } },
  value: string,
  mode: "endsWith" | "equals",
): boolean {
  const { specificationGroup, apiGroup } = config.extensions;
  return mode === "endsWith"
    ? value.endsWith(specificationGroup) || value.endsWith(apiGroup)
    : value === specificationGroup || value === apiGroup;
}

export class FileCacheService {
  private static instance: FileCacheService;
  private readonly serviceCache: Map<string, CacheEntry> = new Map();
  private readonly contextServiceCache: Map<string, CacheEntry> = new Map();
  private readonly mcpServiceCache: Map<string, CacheEntry> = new Map();
  private readonly chainCache: Map<string, CacheEntry> = new Map();
  private readonly specificationGroupCache: Map<string, CacheEntry> = new Map();
  private readonly specificationCache: Map<string, CacheEntry> = new Map();
  private readonly apiCache: Map<string, CacheEntry> = new Map();

  private constructor() {}

  static getInstance(): FileCacheService {
    if (!FileCacheService.instance) {
      FileCacheService.instance = new FileCacheService();
    }
    return FileCacheService.instance;
  }

  private getTTL(): number {
    try {
      const config = ProjectConfigService.getConfig();
      return config.cache?.ttl ?? 60000;
    } catch {
      return 60000;
    }
  }

  private isExpired(entry: CacheEntry): boolean {
    const ttl = this.getTTL();
    return Date.now() - entry.timestamp > ttl;
  }

  private setUri(id: string, uri: Uri, cache: Map<string, CacheEntry>) {
    cache.set(id, {
      uri,
      timestamp: Date.now(),
    });
  }

  private getUri(id: string, cache: Map<string, CacheEntry>): Uri | null {
    const entry = cache.get(id);
    if (!entry) {
      return null;
    }

    if (this.isExpired(entry)) {
      cache.delete(id);
      return null;
    }

    return entry.uri;
  }

  setContextServiceUri(serviceId: string, uri: Uri): void {
    this.setUri(serviceId, uri, this.contextServiceCache);
  }

  getContextServiceUri(serviceId: string): Uri | null {
    return this.getUri(serviceId, this.contextServiceCache);
  }

  clearContextServiceCache(): void {
    this.contextServiceCache.clear();
  }

  setMCPServiceUri(serviceId: string, uri: Uri): void {
    this.setUri(serviceId, uri, this.mcpServiceCache);
  }

  getMCPServiceUri(serviceId: string): Uri | null {
    return this.getUri(serviceId, this.mcpServiceCache);
  }

  clearMCPServiceCache(): void {
    this.mcpServiceCache.clear();
  }

  getServiceUri(serviceId: string): Uri | null {
    return this.getUri(serviceId, this.serviceCache);
  }

  setServiceUri(serviceId: string, uri: Uri): void {
    this.setUri(serviceId, uri, this.serviceCache);
  }

  invalidateService(serviceId: string): void {
    this.serviceCache.delete(serviceId);
  }

  clearServiceCache(): void {
    this.serviceCache.clear();
  }

  getChainUri(chainId: string): Uri | null {
    return this.getUri(chainId, this.chainCache);
  }

  setChainUri(chainId: string, uri: Uri): void {
    this.setUri(chainId, uri, this.chainCache);
  }

  invalidateChain(chainId: string): void {
    this.chainCache.delete(chainId);
  }

  clearChainCache(): void {
    this.chainCache.clear();
  }

  getSpecificationGroupUri(groupId: string): Uri | null {
    return this.getUri(groupId, this.specificationGroupCache);
  }

  setSpecificationGroupUri(groupId: string, uri: Uri): void {
    this.setUri(groupId, uri, this.specificationGroupCache);
  }

  invalidateSpecificationGroup(groupId: string): void {
    this.specificationGroupCache.delete(groupId);
  }

  clearSpecificationGroupCache(): void {
    this.specificationGroupCache.clear();
  }

  getSpecificationUri(specificationId: string): Uri | null {
    return this.getUri(specificationId, this.specificationCache);
  }

  setSpecificationUri(specificationId: string, uri: Uri): void {
    this.setUri(specificationId, uri, this.specificationCache);
  }

  invalidateSpecification(specificationId: string): void {
    this.specificationCache.delete(specificationId);
  }

  clearSpecificationCache(): void {
    this.specificationCache.clear();
  }

  getApiUri(apiId: string): Uri | null {
    return this.getUri(apiId, this.apiCache);
  }

  setApiUri(apiId: string, uri: Uri): void {
    this.setUri(apiId, uri, this.apiCache);
  }

  invalidateApi(apiId: string): void {
    this.apiCache.delete(apiId);
  }

  clearApiCache(): void {
    this.apiCache.clear();
  }

  invalidateByUri(uri: Uri): void {
    try {
      const filename = extractFilename(uri);
      const config = ProjectConfigService.getConfig();

      if (filename.endsWith(config.extensions.service)) {
        this.invalidateByUriInCache(this.serviceCache, uri);
      } else if (filename.endsWith(config.extensions.contextService)) {
        this.invalidateByUriInCache(this.contextServiceCache, uri);
      } else if (filename.endsWith(config.extensions.mcpService)) {
        this.invalidateByUriInCache(this.mcpServiceCache, uri);
      } else if (filename.endsWith(config.extensions.chain)) {
        this.invalidateByUriInCache(this.chainCache, uri);
      } else if (isGroupExtension(config, filename, "endsWith")) {
        this.invalidateByUriInCache(this.specificationGroupCache, uri);
      } else if (filename.endsWith(config.extensions.specification)) {
        this.invalidateByUriInCache(this.specificationCache, uri);
      } else if (filename.endsWith(config.extensions.api)) {
        this.invalidateByUriInCache(this.apiCache, uri);
      }
    } catch (error) {
      console.error(
        "[FileCacheService] Error invalidating cache by URI:",
        error,
      );
    }
  }

  private invalidateByUriInCache(
    cache: Map<string, CacheEntry>,
    uri: Uri,
  ): void {
    const uriString = uri.toString();
    for (const [key, entry] of cache.entries()) {
      if (entry.uri.toString() === uriString) {
        cache.delete(key);
        break;
      }
    }
  }

  getFileUri(id: string, extension?: string): Uri | null {
    if (!extension) {
      return null;
    }

    try {
      const config = ProjectConfigService.getConfig();

      if (extension === config.extensions.service) {
        return this.getServiceUri(id);
      } else if (extension === config.extensions.contextService) {
        return this.getContextServiceUri(id);
      } else if (extension === config.extensions.mcpService) {
        return this.getMCPServiceUri(id);
      } else if (extension === config.extensions.chain) {
        return this.getChainUri(id);
      } else if (isGroupExtension(config, extension, "equals")) {
        return this.getSpecificationGroupUri(id);
      } else if (extension === config.extensions.specification) {
        return this.getSpecificationUri(id);
      } else if (extension === config.extensions.api) {
        return this.getApiUri(id);
      }
    } catch (error) {
      console.error("[FileCacheService] Error getting file URI:", error);
    }

    return null;
  }

  setFileUri(id: string, extension: string | undefined, uri: Uri): void {
    if (!extension) {
      return;
    }

    try {
      const config = ProjectConfigService.getConfig();

      if (extension === config.extensions.service) {
        this.setServiceUri(id, uri);
      } else if (extension === config.extensions.contextService) {
        this.setContextServiceUri(id, uri);
      } else if (extension === config.extensions.mcpService) {
        this.setMCPServiceUri(id, uri);
      } else if (extension === config.extensions.chain) {
        this.setChainUri(id, uri);
      } else if (isGroupExtension(config, extension, "equals")) {
        this.setSpecificationGroupUri(id, uri);
      } else if (extension === config.extensions.specification) {
        this.setSpecificationUri(id, uri);
      } else if (extension === config.extensions.api) {
        this.setApiUri(id, uri);
      }
    } catch (error) {
      console.error("[FileCacheService] Error setting file URI:", error);
    }
  }

  clearAll(): void {
    this.clearServiceCache();
    this.clearContextServiceCache();
    this.clearMCPServiceCache();
    this.clearChainCache();
    this.clearSpecificationGroupCache();
    this.clearSpecificationCache();
    this.clearApiCache();
  }
}
