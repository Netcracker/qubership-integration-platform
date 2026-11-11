import { Uri } from "vscode";
import {
  FILE_EXTENSIONS,
  extractFilename,
} from "../response/file/fileExtensions";

const CACHE_TTL_MS = 60000;

interface CacheEntry {
  uri: Uri;
  timestamp: number;
}

export class FileCacheService {
  private static instance: FileCacheService;
  private serviceCache: Map<string, CacheEntry> = new Map();
  private contextServiceCache: Map<string, CacheEntry> = new Map();
  private mcpServiceCache: Map<string, CacheEntry> = new Map();
  private chainCache: Map<string, CacheEntry> = new Map();
  private specificationGroupCache: Map<string, CacheEntry> = new Map();
  private specificationCache: Map<string, CacheEntry> = new Map();

  private constructor() {}

  static getInstance(): FileCacheService {
    if (!FileCacheService.instance) {
      FileCacheService.instance = new FileCacheService();
    }
    return FileCacheService.instance;
  }

  private isExpired(entry: CacheEntry): boolean {
    return Date.now() - entry.timestamp > CACHE_TTL_MS;
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

  invalidateByUri(uri: Uri): void {
    const filename = extractFilename(uri);

    if (filename.endsWith(FILE_EXTENSIONS.service)) {
      this.invalidateByUriInCache(this.serviceCache, uri);
    } else if (filename.endsWith(FILE_EXTENSIONS.contextService)) {
      this.invalidateByUriInCache(this.contextServiceCache, uri);
    } else if (filename.endsWith(FILE_EXTENSIONS.mcpService)) {
      this.invalidateByUriInCache(this.mcpServiceCache, uri);
    } else if (filename.endsWith(FILE_EXTENSIONS.chain)) {
      this.invalidateByUriInCache(this.chainCache, uri);
    } else if (filename.endsWith(FILE_EXTENSIONS.specificationGroup)) {
      this.invalidateByUriInCache(this.specificationGroupCache, uri);
    } else if (filename.endsWith(FILE_EXTENSIONS.specification)) {
      this.invalidateByUriInCache(this.specificationCache, uri);
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

    if (extension === FILE_EXTENSIONS.service) {
      return this.getServiceUri(id);
    } else if (extension === FILE_EXTENSIONS.contextService) {
      return this.getContextServiceUri(id);
    } else if (extension === FILE_EXTENSIONS.mcpService) {
      return this.getMCPServiceUri(id);
    } else if (extension === FILE_EXTENSIONS.chain) {
      return this.getChainUri(id);
    } else if (extension === FILE_EXTENSIONS.specificationGroup) {
      return this.getSpecificationGroupUri(id);
    } else if (extension === FILE_EXTENSIONS.specification) {
      return this.getSpecificationUri(id);
    }

    return null;
  }

  setFileUri(id: string, extension: string | undefined, uri: Uri): void {
    if (!extension) {
      return;
    }

    if (extension === FILE_EXTENSIONS.service) {
      this.setServiceUri(id, uri);
    } else if (extension === FILE_EXTENSIONS.contextService) {
      this.setContextServiceUri(id, uri);
    } else if (extension === FILE_EXTENSIONS.mcpService) {
      this.setMCPServiceUri(id, uri);
    } else if (extension === FILE_EXTENSIONS.chain) {
      this.setChainUri(id, uri);
    } else if (extension === FILE_EXTENSIONS.specificationGroup) {
      this.setSpecificationGroupUri(id, uri);
    } else if (extension === FILE_EXTENSIONS.specification) {
      this.setSpecificationUri(id, uri);
    }
  }

  clearAll(): void {
    this.clearServiceCache();
    this.clearContextServiceCache();
    this.clearMCPServiceCache();
    this.clearChainCache();
    this.clearSpecificationGroupCache();
    this.clearSpecificationCache();
  }
}
