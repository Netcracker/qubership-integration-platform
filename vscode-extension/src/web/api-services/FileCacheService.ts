import { Uri } from "vscode";

interface CacheEntry {
    uri: Uri;
    timestamp: number;
}

export class FileCacheService {
    private static instance: FileCacheService;
    private serviceCache: Map<string, CacheEntry> = new Map();
    private specificationGroupCache: Map<string, CacheEntry> = new Map();
    private readonly TTL = 60000;

    private constructor() {}

    static getInstance(): FileCacheService {
        if (!FileCacheService.instance) {
            FileCacheService.instance = new FileCacheService();
        }
        return FileCacheService.instance;
    }

    getServiceUri(serviceId: string): Uri | null {
        const entry = this.serviceCache.get(serviceId);
        if (!entry) {
            return null;
        }
        
        if (Date.now() - entry.timestamp > this.TTL) {
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

    getSpecificationGroupUri(groupId: string): Uri | null {
        const entry = this.specificationGroupCache.get(groupId);
        if (!entry) {
            return null;
        }
        
        if (Date.now() - entry.timestamp > this.TTL) {
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

    clearAll(): void {
        this.clearServiceCache();
        this.clearSpecificationGroupCache();
    }
}

