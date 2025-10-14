import { ProjectConfigService } from '../../services/ProjectConfigService';
import { Uri } from 'vscode';

export type FileExtensionsConfig = {
    appName: string;
    chain: string;
    service: string;
    specificationGroup: string;
    specification: string;
};

export function buildDefaultExtensions(appName: string): FileExtensionsConfig {
    return {
        appName,
        chain: `.chain.${appName}.yaml`,
        service: `.service.${appName}.yaml`,
        specificationGroup: `.specification-group.${appName}.yaml`,
        specification: `.specification.${appName}.yaml`
    };
}

let defaultAppName = 'qip';
let memoizedDefaultExtensions: FileExtensionsConfig | null = null;

export function setDefaultAppName(appName: string) {
    defaultAppName = appName;
    memoizedDefaultExtensions = null;
}

export function getDefaultAppName(): string {
    return defaultAppName;
}

export function getDefaultExtensions(): FileExtensionsConfig {
    if (!memoizedDefaultExtensions) {
        memoizedDefaultExtensions = buildDefaultExtensions(defaultAppName);
    }
    return memoizedDefaultExtensions;
}

let currentFileContext: string | null = null;

export function setCurrentFileContext(filename: string | null) {
    currentFileContext = filename;
}

export function getCurrentFileContext(): string | null {
    return currentFileContext;
}

export function extractAppNameFromExtension(filename: string): string {
    const vscode = require('vscode');
    const workspaceUri = vscode.workspace.workspaceFolders?.[0]?.uri;
    
    if (workspaceUri) {
        try {
            const configService = ProjectConfigService.getInstance();
            
            if (!configService.isConfigLoaded()) {
                const match = filename.match(/\.(service\d*|chain\d*|specification-group\d*|specification\d*)\.([^.]+)\.yaml$/);
                return match ? match[2] : defaultAppName;
            }
            
            const allConfigs = configService.getAllConfigs();
            
            for (const config of allConfigs) {
                for (const extension of Object.values(config.extensions)) {
                    if (filename.endsWith(extension)) {
                        return config.appName;
                    }
                }
            }
        } catch (error) {
        }
    }
    
    const match = filename.match(/\.(service\d*|chain\d*|specification-group\d*|specification\d*)\.([^.]+)\.yaml$/);
    return match ? match[2] : defaultAppName;
}

export function getExtensionsForFile(filename?: string): FileExtensionsConfig {
    const contextFile = filename || currentFileContext;
    if (contextFile) {
        const appName = extractAppNameFromExtension(contextFile);
        
        try {
            const configService = ProjectConfigService.getInstance();
            
            if (configService.isConfigLoaded()) {
                const allConfigs = configService.getAllConfigs();
                
                const foundConfig = allConfigs.find(cfg => cfg.appName === appName);
                if (foundConfig) {
                    return {
                        appName: foundConfig.appName,
                        chain: foundConfig.extensions.chain,
                        service: foundConfig.extensions.service,
                        specificationGroup: foundConfig.extensions.specificationGroup,
                        specification: foundConfig.extensions.specification
                    };
                }
            }
        } catch (error) {
        }
        
        return buildDefaultExtensions(appName);
    }
    return getDefaultExtensions();
}

export function extractFilename(fileUri: { path: string } | string): string {
    if (typeof fileUri === 'string') {
        return fileUri.split('/').pop() || '';
    }
    return fileUri.path.split('/').pop() || '';
}

export function getExtensionsForUri(fileUri?: { path: string }): FileExtensionsConfig {
    if (fileUri) {
        const filename = extractFilename(fileUri);
        return getExtensionsForFile(filename);
    }
    return getExtensionsForFile();
}

export async function initializeContextFromFile(fileUri: Uri): Promise<void> {
    const vscode = require('vscode');
    const filename = extractFilename(fileUri);
    const appName = extractAppNameFromExtension(filename);
    
    const configService = ProjectConfigService.getInstance();
    const workspaceUri = vscode.workspace.workspaceFolders?.[0]?.uri;
    
    await configService.setCurrentContext(appName, workspaceUri);
    setCurrentFileContext(filename);
    
    const config = configService.getCurrentConfig();
    memoizedDefaultExtensions = {
        appName: config.appName,
        chain: config.extensions.chain,
        service: config.extensions.service,
        specificationGroup: config.extensions.specificationGroup,
        specification: config.extensions.specification
    };
}

export function getExtensionsFromConfig(): FileExtensionsConfig {
    const config = ProjectConfigService.getConfig();
    
    return {
        appName: config.appName,
        chain: config.extensions.chain,
        service: config.extensions.service,
        specificationGroup: config.extensions.specificationGroup,
        specification: config.extensions.specification
    };
}
