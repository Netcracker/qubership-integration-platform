import { Uri } from "vscode";
import { YamlFileUtils } from "./YamlFileUtils";
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
}

const DEFAULT_SCHEMA_URLS = {
    service: 'http://qubership.org/schemas/product/qip/service',
    chain: 'http://qubership.org/schemas/product/qip/chain',
    specification: 'http://qubership.org/schemas/product/qip/specification',
    specificationGroup: 'http://qubership.org/schemas/product/qip/specification-group'
};

const CONFIG_FILENAME = '.qip.config.yaml';

export class ProjectConfigService {
    private static instance: ProjectConfigService;
    private currentAppName: string = 'qip';
    private currentConfig: ProjectConfig | null = null;
    private workspaceConfig: ProjectConfig | null = null;

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

    async setCurrentContext(appName: string, workspaceUri?: Uri): Promise<void> {
        this.currentAppName = appName;
        
        const vscode = require('vscode');
        const rootUri = workspaceUri || vscode.workspace.workspaceFolders?.[0]?.uri;
        
        if (!rootUri) {
            this.currentConfig = this.buildDefaultConfig(appName);
            return;
        }

        if (!this.workspaceConfig) {
            this.workspaceConfig = await this.loadWorkspaceConfig(rootUri);
        }

        if (this.workspaceConfig.appName === appName) {
            this.currentConfig = this.workspaceConfig;
        } else {
            this.currentConfig = this.buildDefaultConfig(appName);
        }
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
        this.workspaceConfig = null;
        this.currentAppName = 'qip';
    }

    private async loadWorkspaceConfig(workspaceUri: Uri): Promise<ProjectConfig> {
        try {
            const configFileUri = Uri.joinPath(workspaceUri, CONFIG_FILENAME);
            const content = await fileApi.readFileContent(configFileUri);
            const yaml = require('yaml');
            const config = yaml.parse(content) as ProjectConfig;
            return config;
        } catch (error) {
            return this.buildDefaultConfig('qip');
        }
    }

    private buildDefaultConfig(appName: string): ProjectConfig {
        return {
            version: '1.0',
            appName: appName,
            extensions: {
                chain: `.chain.${appName}.yaml`,
                service: `.service.${appName}.yaml`,
                specificationGroup: `.specification-group.${appName}.yaml`,
                specification: `.specification.${appName}.yaml`
            },
            schemaUrls: DEFAULT_SCHEMA_URLS
        };
    }

    async saveConfig(config: ProjectConfig, workspaceUri?: Uri): Promise<void> {
        const vscode = require('vscode');
        const rootUri = workspaceUri || vscode.workspace.workspaceFolders?.[0]?.uri;
        
        if (!rootUri) {
            throw new Error('No workspace folder available');
        }

        const configFileUri = Uri.joinPath(rootUri, CONFIG_FILENAME);
        await YamlFileUtils.saveYamlFile(configFileUri, config);
        
        this.workspaceConfig = config;
        if (config.appName === this.currentAppName) {
            this.currentConfig = config;
        }
    }

    async updateAppName(appName: string, workspaceUri?: Uri): Promise<void> {
        const config = this.getCurrentConfig();
        config.appName = appName;
        config.extensions = {
            chain: `.chain.${appName}.yaml`,
            service: `.service.${appName}.yaml`,
            specificationGroup: `.specification-group.${appName}.yaml`,
            specification: `.specification.${appName}.yaml`
        };
        await this.saveConfig(config, workspaceUri);
        await this.setCurrentContext(appName, workspaceUri);
    }

    async updateExtension(
        type: keyof ProjectConfig['extensions'], 
        extension: string, 
        workspaceUri?: Uri
    ): Promise<void> {
        const config = this.getCurrentConfig();
        config.extensions[type] = extension;
        await this.saveConfig(config, workspaceUri);
    }

    async updateSchemaUrl(
        type: keyof ProjectConfig['schemaUrls'], 
        url: string, 
        workspaceUri?: Uri
    ): Promise<void> {
        const config = this.getCurrentConfig();
        config.schemaUrls[type] = url;
        await this.saveConfig(config, workspaceUri);
    }
}

