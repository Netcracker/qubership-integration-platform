import { Uri } from "vscode";
import { ProjectConfigService } from "./ProjectConfigService";
import { extractFilename } from "../response/file/fileExtensions";

interface CacheEntry {
    uri: Uri;
    timestamp: number;
}

export class FileCacheService {
    private static instance: FileCacheService;
    private serviceCache: Map<string, CacheEntry> = new Map();
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

    getServiceUri(serviceId: string): Uri | null {
        const entry = this.serviceCache.get(serviceId);
        if (!entry) {
            return null;
        }
        
        if (this.isExpired(entry)) {
            this.serviceCache.delete(serviceId);
            return null;
        }
        
        return entry.uri;
    }

    setServiceUri(serviceId: string, uri: Uri): void {
        this.serviceCache.set(serviceId, {
            uri,
            timestamp: Date.now()
        });
    }

    invalidateService(serviceId: string): void {
        this.serviceCache.delete(serviceId);
    }

    clearServiceCache(): void {
        this.serviceCache.clear();
    }

    getChainUri(chainId: string): Uri | null {
        const entry = this.chainCache.get(chainId);
        if (!entry) {
            return null;
        }
        
        if (this.isExpired(entry)) {
            this.chainCache.delete(chainId);
            return null;
        }
        
        return entry.uri;
    }

    setChainUri(chainId: string, uri: Uri): void {
        this.chainCache.set(chainId, {
            uri,
            timestamp: Date.now()
        });
    }

    invalidateChain(chainId: string): void {
        this.chainCache.delete(chainId);
    }

    clearChainCache(): void {
        this.chainCache.clear();
    }

    getSpecificationGroupUri(groupId: string): Uri | null {
        const entry = this.specificationGroupCache.get(groupId);
        if (!entry) {
            return null;
        }
        
        if (this.isExpired(entry)) {
            this.specificationGroupCache.delete(groupId);
            return null;
        }
        
        return entry.uri;
    }

    setSpecificationGroupUri(groupId: string, uri: Uri): void {
        this.specificationGroupCache.set(groupId, {
            uri,
            timestamp: Date.now()
        });
    }

    invalidateSpecificationGroup(groupId: string): void {
        this.specificationGroupCache.delete(groupId);
    }

    clearSpecificationGroupCache(): void {
        this.specificationGroupCache.clear();
    }

    getSpecificationUri(specificationId: string): Uri | null {
        const entry = this.specificationCache.get(specificationId);
        if (!entry) {
            return null;
        }
        
        if (this.isExpired(entry)) {
            this.specificationCache.delete(specificationId);
            return null;
        }
        
        return entry.uri;
    }

    setSpecificationUri(specificationId: string, uri: Uri): void {
        this.specificationCache.set(specificationId, {
            uri,
            timestamp: Date.now()
        });
    }

    invalidateSpecification(specificationId: string): void {
        this.specificationCache.delete(specificationId);
    }

    clearSpecificationCache(): void {
        this.specificationCache.clear();
    }

    invalidateByUri(uri: Uri): void {
        try {
            const filename = extractFilename(uri);
            const config = ProjectConfigService.getConfig();
            
            if (filename.endsWith(config.extensions.service)) {
                this.invalidateByUriInCache(this.serviceCache, uri);
            } else if (filename.endsWith(config.extensions.chain)) {
                this.invalidateByUriInCache(this.chainCache, uri);
            } else if (filename.endsWith(config.extensions.specificationGroup)) {
                this.invalidateByUriInCache(this.specificationGroupCache, uri);
            } else if (filename.endsWith(config.extensions.specification)) {
                this.invalidateByUriInCache(this.specificationCache, uri);
            }
        } catch (error) {
            console.error('[FileCacheService] Error invalidating cache by URI:', error);
        }
    }

    private invalidateByUriInCache(cache: Map<string, CacheEntry>, uri: Uri): void {
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
            } else if (extension === config.extensions.chain) {
                return this.getChainUri(id);
            } else if (extension === config.extensions.specificationGroup) {
                return this.getSpecificationGroupUri(id);
            } else if (extension === config.extensions.specification) {
                return this.getSpecificationUri(id);
            }
        } catch (error) {
            console.error('[FileCacheService] Error getting file URI:', error);
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
            } else if (extension === config.extensions.chain) {
                this.setChainUri(id, uri);
            } else if (extension === config.extensions.specificationGroup) {
                this.setSpecificationGroupUri(id, uri);
            } else if (extension === config.extensions.specification) {
                this.setSpecificationUri(id, uri);
            }
        } catch (error) {
            console.error('[FileCacheService] Error setting file URI:', error);
        }
    }

    clearAll(): void {
        this.clearServiceCache();
        this.clearChainCache();
        this.clearSpecificationGroupCache();
        this.clearSpecificationCache();
    }
}

