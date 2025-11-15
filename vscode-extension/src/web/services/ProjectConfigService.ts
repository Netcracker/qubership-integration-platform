import { Uri, ExtensionContext } from "vscode";
import * as vscode from "vscode";
import * as yaml from "yaml";
import { YamlFileUtils } from "../api-services/YamlFileUtils";
import { fileApi } from "../response/file/fileApiProvider";

export interface ProjectConfig {
    version: string;
    appName: string;
    extensions: {
        chain: string;
        service: string;
        specificationGroup: string;
        specification: string;
    };
    schemaUrls: {
        service: string;
        chain: string;
        specification: string;
        specificationGroup: string;
    };
    cache?: {
        ttl?: number;
    };
}

export interface ProjectConfigFile {
    version: string;
    configs: {
        [appName: string]: {
            extensions: {
                chain: string;
                service: string;
                specificationGroup: string;
                specification: string;
            };
            schemaUrls: {
                service: string;
                chain: string;
                specification: string;
                specificationGroup: string;
            };
        };
    };
    cache?: {
        ttl?: number;
    };
}

const DEFAULT_SCHEMA_URLS = {
    service: 'http://qubership.org/schemas/product/qip/service',
    chain: 'http://qubership.org/schemas/product/qip/chain',
    specification: 'http://qubership.org/schemas/product/qip/specification',
    specificationGroup: 'http://qubership.org/schemas/product/qip/specification-group'
};

export const CONFIG_FILENAME = '.config.qip.yaml';

export class ProjectConfigService {
    private static instance: ProjectConfigService;
    private context?: ExtensionContext;
    private currentAppName: string = 'qip';
    private currentConfig: ProjectConfig | null = null;
    private workspaceConfigs: Map<string, ProjectConfig> = new Map();
    private externalConfigs: Map<string, ProjectConfig> = new Map();
    private cacheTtl: number = 60000;
    private wasWorkspaceConfigLoaded: boolean = false;

    private constructor() {}

    static getInstance(): ProjectConfigService {
        if (!ProjectConfigService.instance) {
            ProjectConfigService.instance = new ProjectConfigService();
        }
        return ProjectConfigService.instance;
    }

    static getConfig(): ProjectConfig {
        return ProjectConfigService.getInstance().getCurrentConfig();
    }

    setContext(context: ExtensionContext): void {
        this.context = context;
    }

    getAllConfigs(): ProjectConfig[] {
        return Array.from(this.workspaceConfigs.values());
    }

    isConfigLoaded(): boolean {
        return this.workspaceConfigs.size > 0;
    }

    async setCurrentContext(appName: string, workspaceUri?: Uri): Promise<void> {
        console.log(`[ProjectConfigService] setCurrentContext called: appName="${appName}", current="${this.currentAppName}"`);
        
        if (this.shouldSkipContextSwitch(appName)) {
            console.log(`[ProjectConfigService] Skipping context switch - external config is active`);
            return;
        }
        
        this.currentAppName = appName;
        
        const rootUri = workspaceUri || vscode.workspace.workspaceFolders?.[0]?.uri;
        
        if (!rootUri) {
            this.currentConfig = this.buildDefaultConfig(appName);
            return;
        }

        if (!this.wasWorkspaceConfigLoaded) {
            await this.loadWorkspaceConfig(rootUri);
            this.wasWorkspaceConfigLoaded = true;
        }

        if (this.workspaceConfigs.has(this.currentAppName)) {
            this.currentConfig = this.workspaceConfigs.get(this.currentAppName)!;
        } else {
            this.currentConfig = this.buildDefaultConfig(this.currentAppName);
        }
        
        console.log(`[ProjectConfigService] Context switched: appName="${this.currentAppName}"`);
    }

    getCurrentConfig(): ProjectConfig {
        if (!this.currentConfig) {
            return this.buildDefaultConfig(this.currentAppName);
        }
        return this.currentConfig;
    }

    getCurrentAppName(): string {
        return this.currentAppName;
    }

    clearCache(): void {
        this.currentConfig = null;
        this.workspaceConfigs.clear();
        this.externalConfigs.clear();
        this.currentAppName = 'qip';
        this.cacheTtl = 60000;
        this.wasWorkspaceConfigLoaded = false;
    }

    async loadEmbeddedConfig(): Promise<void> {
        if (!this.context) {
            console.warn('[ProjectConfigService] Extension context not set, using hardcoded defaults');
            this.workspaceConfigs.set('qip', this.buildDefaultConfig('qip'));
            return;
        }

        const configUri = Uri.joinPath(this.context.extensionUri, 'configs', 'default.config.qip.yaml');
        const configFile = await this.loadConfigFileFromUri(configUri);
        
        if (configFile) {
            this.updateCacheTtl(configFile);
            this.parseConfigFile(configFile, this.workspaceConfigs);
            console.log('[ProjectConfigService] Embedded config loaded successfully');
        } else {
            console.warn('[ProjectConfigService] Failed to load embedded config, using hardcoded defaults');
            this.workspaceConfigs.set('qip', this.buildDefaultConfig('qip'));
        }
    }

    async loadConfigFromUri(configUri: Uri): Promise<void> {
        const configFile = await this.loadConfigFileFromUri(configUri);
        
        if (!configFile) {
            console.warn('[ProjectConfigService] Failed to load external config from', configUri.toString());
            return;
        }

        this.updateCacheTtl(configFile);

        for (const [appName, configData] of Object.entries(configFile.configs)) {
            const config = this.buildConfigFromData(appName, configData, configFile.version, configFile.cache);
            this.addExternalConfig(appName, config);
        }
        
        console.log('[ProjectConfigService] External config loaded from', configUri.toString());
    }

    private async loadConfigFileFromUri(configUri: Uri): Promise<ProjectConfigFile | null> {
        try {
            const content = await fileApi.readFileContent(configUri);
            const configFile = yaml.parse(content) as ProjectConfigFile;
            
            if (!configFile || !configFile.configs) {
                console.warn('[ProjectConfigService] Invalid config structure at', configUri.toString());
                return null;
            }
            
            return configFile;
        } catch (error) {
            console.warn('[ProjectConfigService] Error reading config from', configUri.toString(), error);
            return null;
        }
    }

    private updateCacheTtl(configFile: ProjectConfigFile): void {
        if (configFile.cache?.ttl) {
            this.cacheTtl = configFile.cache.ttl;
        }
    }

    private buildConfigFromData(
        appName: string,
        configData: ProjectConfigFile['configs'][string],
        version: string,
        cache?: ProjectConfigFile['cache']
    ): ProjectConfig {
        const config: ProjectConfig = {
            version,
            appName,
            extensions: configData.extensions,
            schemaUrls: configData.schemaUrls,
            cache
        };
        
        return this.substituteVariables(config);
    }

    private addExternalConfig(appName: string, config: ProjectConfig): void {
        const isFirstExternalConfig = this.externalConfigs.size === 0;
        
        this.externalConfigs.set(appName, config);
        this.workspaceConfigs.set(appName, config);
        
        this.updateCurrentConfigIfNeeded(appName, config, isFirstExternalConfig);
        
        console.log(`[ProjectConfigService] External config added: appName="${appName}", extensions:`, config.extensions);
    }

    private updateCurrentConfigIfNeeded(appName: string, config: ProjectConfig, isFirstExternalConfig: boolean): void {
        if (this.shouldUpdateCurrentConfig(appName, isFirstExternalConfig)) {
            const wasAutoSwitched = this.currentAppName !== appName;
            
            this.currentAppName = appName;
            this.currentConfig = config;
            
            if (wasAutoSwitched) {
                console.log(`[ProjectConfigService] Auto-switched to external config: appName="${appName}"`);
            }
        }
    }

    private shouldUpdateCurrentConfig(appName: string, isFirstExternalConfig: boolean): boolean {
        if (this.currentAppName === appName) {
            return true;
        }
        
        const isDefaultContext = this.currentAppName === 'qip';
        const isNonDefaultConfig = appName !== 'qip';
        
        return isDefaultContext && isFirstExternalConfig && isNonDefaultConfig;
    }

    private shouldSkipContextSwitch(requestedAppName: string): boolean {
        if (requestedAppName === this.currentAppName) {
            return false;
        }
        
        const hasExternalConfig = this.externalConfigs.size > 0;
        const isAutoCallWithDefault = requestedAppName === 'qip';
        const alreadySwitchedToExternal = this.currentAppName !== 'qip' && this.externalConfigs.has(this.currentAppName);
        
        return hasExternalConfig && isAutoCallWithDefault && alreadySwitchedToExternal;
    }

    private parseConfigFile(configFile: ProjectConfigFile, targetMap: Map<string, ProjectConfig>): void {
        for (const [appName, configData] of Object.entries(configFile.configs)) {
            const config = this.buildConfigFromData(appName, configData, configFile.version, configFile.cache);
            targetMap.set(appName, config);
            console.log(`[ProjectConfigService] Config parsed: appName="${appName}", extensions:`, config.extensions);
        }
    }

    registerExternalConfig(appName: string, configData: {
        extensions?: Partial<ProjectConfig['extensions']>;
        schemaUrls?: Partial<ProjectConfig['schemaUrls']>;
    }): void {
        const baseConfig = this.buildDefaultConfig(appName);
        const config: ProjectConfig = {
            version: '1.0',
            appName,
            extensions: { ...baseConfig.extensions, ...configData.extensions },
            schemaUrls: { ...baseConfig.schemaUrls, ...configData.schemaUrls },
            cache: { ttl: this.cacheTtl }
        };

        this.addExternalConfig(appName, this.substituteVariables(config));
    }

    unregisterExternalConfig(appName: string): void {
        this.externalConfigs.delete(appName);
        this.workspaceConfigs.delete(appName);
        
        if (this.currentAppName === appName) {
            this.currentAppName = 'qip';
            this.currentConfig = null;
        }
        
        console.log(`[ProjectConfigService] External config unregistered: appName="${appName}"`);
    }

    getConfigByAppName(appName: string): ProjectConfig | undefined {
        return this.workspaceConfigs.get(appName);
    }

    async loadWorkspaceConfig(workspaceUri: Uri): Promise<void> {
        await this.loadEmbeddedConfig();
        this.applyExternalConfigs();

        const configFileUri = Uri.joinPath(workspaceUri, CONFIG_FILENAME);
        const configFile = await this.loadConfigFileFromUri(configFileUri);
        
        if (configFile) {
            this.updateCacheTtl(configFile);
            this.loadWorkspaceConfigsFromFile(configFile);
        } else {
            console.log('[ProjectConfigService] No workspace config found, using embedded and external configs');
        }

        console.log(`[ProjectConfigService] Total configs loaded: ${this.workspaceConfigs.size}`);
    }

    private applyExternalConfigs(): void {
        for (const [appName, config] of this.externalConfigs.entries()) {
            if (!this.workspaceConfigs.has(appName)) {
                this.workspaceConfigs.set(appName, config);
            }
        }
    }

    private loadWorkspaceConfigsFromFile(configFile: ProjectConfigFile): void {
        for (const [appName, configData] of Object.entries(configFile.configs)) {
            const config = this.buildConfigFromData(appName, configData, configFile.version, configFile.cache);
            this.workspaceConfigs.set(appName, config);
            this.updateCurrentIfMatches(appName, config);
            console.log(`[ProjectConfigService] Workspace config loaded: appName="${appName}", extensions:`, config.extensions);
        }
    }

    private updateCurrentIfMatches(appName: string, config: ProjectConfig): void {
        if (this.currentAppName === appName) {
            this.currentConfig = config;
        }
    }

    private substituteVariables(config: ProjectConfig): ProjectConfig {
        const variables: Record<string, string> = {
            appName: config.appName,
            version: config.version
        };

        const substitute = (value: any): any => {
            if (typeof value === 'string') {
                return value.replace(/\$\{(\w+)\}/g, (match, varName) => {
                    return variables[varName] !== undefined ? variables[varName] : match;
                });
            }
            if (Array.isArray(value)) {
                return value.map(substitute);
            }
            if (typeof value === 'object' && value !== null) {
                const result: any = {};
                for (const key in value) {
                    result[key] = substitute(value[key]);
                }
                return result;
            }
            return value;
        };

        return substitute(config);
    }

    buildDefaultConfig(appName: string): ProjectConfig {
        return {
            version: '1.0',
            appName: appName,
            extensions: {
                chain: `.chain.${appName}.yaml`,
                service: `.service.${appName}.yaml`,
                specificationGroup: `.specification-group.${appName}.yaml`,
                specification: `.specification.${appName}.yaml`
            },
            schemaUrls: DEFAULT_SCHEMA_URLS,
            cache: {
                ttl: 60000
            }
        };
    }

    async saveConfig(config: ProjectConfig, workspaceUri?: Uri): Promise<void> {
        const rootUri = this.getWorkspaceUri(workspaceUri);
        
        this.workspaceConfigs.set(config.appName, config);
        this.updateCurrentIfMatches(config.appName, config);

        const configFile = this.buildConfigFileFromWorkspace(config.version);
        const configFileUri = Uri.joinPath(rootUri, CONFIG_FILENAME);
        
        await YamlFileUtils.saveYamlFile(configFileUri, configFile);
    }

    private getWorkspaceUri(workspaceUri?: Uri): Uri {
        const rootUri = workspaceUri || vscode.workspace.workspaceFolders?.[0]?.uri;
        
        if (!rootUri) {
            throw new Error('No workspace folder available');
        }
        
        return rootUri;
    }

    private buildConfigFileFromWorkspace(version: string): ProjectConfigFile {
        const configFile: ProjectConfigFile = {
            version,
            configs: {},
            cache: { ttl: this.cacheTtl }
        };
        
        for (const [appName, cfg] of this.workspaceConfigs.entries()) {
            configFile.configs[appName] = {
                extensions: cfg.extensions,
                schemaUrls: cfg.schemaUrls
            };
        }
        
        return configFile;
    }

    async updateAppName(appName: string, workspaceUri?: Uri): Promise<void> {
        const oldAppName = this.currentAppName;
        const defaultExtensions = this.buildDefaultConfig(appName).extensions;
        
        const newConfig: ProjectConfig = {
            ...this.getCurrentConfig(),
            appName,
            extensions: defaultExtensions
        };
        
        this.workspaceConfigs.delete(oldAppName);
        await this.saveConfig(newConfig, workspaceUri);
        await this.setCurrentContext(appName, workspaceUri);
    }

    async updateExtension(
        type: keyof ProjectConfig['extensions'], 
        extension: string, 
        workspaceUri?: Uri
    ): Promise<void> {
        await this.updateConfigProperty('extensions', type, extension, workspaceUri);
    }

    async updateSchemaUrl(
        type: keyof ProjectConfig['schemaUrls'], 
        url: string, 
        workspaceUri?: Uri
    ): Promise<void> {
        await this.updateConfigProperty('schemaUrls', type, url, workspaceUri);
    }

    private async updateConfigProperty<K extends 'extensions' | 'schemaUrls'>(
        propertyName: K,
        type: keyof ProjectConfig[K],
        value: string,
        workspaceUri?: Uri
    ): Promise<void> {
        const config = this.getCurrentConfig();
        const updatedConfig: ProjectConfig = {
            ...config,
            [propertyName]: {
                ...config[propertyName],
                [type]: value
            }
        };
        await this.saveConfig(updatedConfig, workspaceUri);
    }
}

