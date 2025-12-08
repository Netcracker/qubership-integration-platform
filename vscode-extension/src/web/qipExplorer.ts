import * as vscode from 'vscode';
import * as yaml from 'yaml';
import { fileApi } from './response/file/fileApiProvider';
import { getExtensionsForFile } from './response/file/fileExtensions';
import { readDirectory } from './response/file/fileApiImpl';
import { ContentParser } from './api-services/parsers/ContentParser';

export interface QipExplorerItem {
    id: string;
    label: string;
    description?: string;
    iconPath?: vscode.ThemeIcon;
    contextValue: string;
    collapsibleState: vscode.TreeItemCollapsibleState;
    children?: QipExplorerItem[];
    fileUri?: vscode.Uri;
    type: 'category' | 'service' | 'chain' | 'element';
}

let globalQipExplorerProvider: QipExplorerProvider | null = null;

export class QipExplorerProvider implements vscode.TreeDataProvider<QipExplorerItem> {
    private _onDidChangeTreeData: vscode.EventEmitter<QipExplorerItem | undefined | null | void> = new vscode.EventEmitter<QipExplorerItem | undefined | null | void>();
    readonly onDidChangeTreeData: vscode.Event<QipExplorerItem | undefined | null | void> = this._onDidChangeTreeData.event;

    constructor(private context: vscode.ExtensionContext) {
        globalQipExplorerProvider = this;
    }

    refresh(): void {
        this._onDidChangeTreeData.fire();
    }

    getTreeItem(element: QipExplorerItem): vscode.TreeItem {
        const treeItem = new vscode.TreeItem(
            element.label,
            element.collapsibleState
        );

        treeItem.description = element.description;
        treeItem.iconPath = element.iconPath;
        treeItem.contextValue = element.contextValue;
        treeItem.tooltip = element.description || element.label;

        if (element.fileUri) {
            treeItem.command = {
                command: 'qip.revealInExplorer',
                title: 'Reveal in File Explorer',
                arguments: [element]
            };
        }

        return treeItem;
    }

    async getChildren(element?: QipExplorerItem): Promise<QipExplorerItem[]> {
        if (!element) {
            return this.getRootItems();
        }

        switch (element.type) {
            case 'category':
                if (element.label === 'Chains') {
                    return this.getChains();
                } else if (element.label === 'Services') {
                    return this.getServices();
                }
                return [];
            case 'service':
                return [];
            case 'chain':
                return this.getChainChildren(element);
            default:
                return [];
        }
    }

    private getRootItems(): QipExplorerItem[] {
        return [
            {
                id: 'chains-category',
                label: 'Chains',
                iconPath: new vscode.ThemeIcon('git-branch'),
                contextValue: 'qip-chains-category',
                collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
                type: 'category'
            },
            {
                id: 'services-category',
                label: 'Services',
                iconPath: new vscode.ThemeIcon('server'),
                contextValue: 'qip-services-category',
                collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
                type: 'category'
            }
        ];
    }

    private async getChains(): Promise<QipExplorerItem[]> {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders || workspaceFolders.length === 0) {
            console.log('QIP Explorer: No workspace folders found');
            return [];
        }
        console.log(`QIP Explorer: Found ${workspaceFolders.length} workspace folders`);

        const chains: QipExplorerItem[] = [];

        for (const folder of workspaceFolders) {
            try {
                console.log(`QIP Explorer: Searching for chains in folder: ${folder.uri.fsPath}`);
                await this.findChainFilesRecursively(folder.uri, chains);
            } catch (error) {
                console.error('Failed to read workspace folder:', error);
            }
        }

        console.log(`QIP Explorer: Total chains found: ${chains.length}`);
        return chains.sort((a, b) => a.label.localeCompare(b.label));
    }

    private async findChainFilesRecursively(folderUri: vscode.Uri, chains: QipExplorerItem[]): Promise<void> {
        try {
            const entries = await readDirectory(folderUri);

            const ext = getExtensionsForFile();
            for (const [name, type] of entries) {
                if (type === vscode.FileType.File && name.endsWith(ext.chain)) {
                    try {
                        const fileUri = vscode.Uri.joinPath(folderUri, name);
                        console.log(`QIP Explorer: Found chain file: ${name}`);
                        const chainData = await ContentParser.parseContentFromFile(fileUri);

                        if (chainData && chainData.content) {
                            const elementsCount = chainData.content.elements?.length || 0;
                            const connectionsCount = chainData.content.dependencies?.length || 0;

                            // Format: ${name}-${uuid}
                            const displayName = chainData.name || chainData.id;
                            const label = `${displayName}-${chainData.id}`;

                            const chainItem: QipExplorerItem = {
                                id: chainData.id,
                                label: label,
                                description: `${elementsCount} elements, ${connectionsCount} connections`,
                                iconPath: new vscode.ThemeIcon('git-branch'),
                                contextValue: 'qip-chain',
                                collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
                                type: 'chain',
                                fileUri: fileUri
                            };
                            chains.push(chainItem);
                            console.log(`QIP Explorer: Added chain: ${label}`);
                        }
                    } catch (error) {
                        console.error(`Failed to parse chain file ${name}:`, error);
                    }
                } else if (type === vscode.FileType.Directory) {
                    // Recursively search in subdirectories
                    const subFolderUri = vscode.Uri.joinPath(folderUri, name);
                    await this.findChainFilesRecursively(subFolderUri, chains);
                }
            }
        } catch (error) {
            console.error(`Failed to read directory ${folderUri.fsPath}:`, error);
        }
    }

    private async getServices(): Promise<QipExplorerItem[]> {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders || workspaceFolders.length === 0) {
            console.log('QIP Explorer: No workspace folders found for services');
            return [];
        }
        console.log(`QIP Explorer: Searching for services in ${workspaceFolders.length} workspace folders`);

        const services: QipExplorerItem[] = [];

        for (const folder of workspaceFolders) {
            try {
                console.log(`QIP Explorer: Searching for services in folder: ${folder.uri.fsPath}`);
                await this.findServiceFilesRecursively(folder.uri, services);
            } catch (error) {
                console.error('Failed to read workspace folder:', error);
            }
        }

        console.log(`QIP Explorer: Total services found: ${services.length}`);
        return services.sort((a, b) => a.label.localeCompare(b.label));
    }

    private async findServiceFilesRecursively(folderUri: vscode.Uri, services: QipExplorerItem[]): Promise<void> {
        try {
            const entries = await readDirectory(folderUri);

            const ext = getExtensionsForFile();
            for (const [name, type] of entries) {
                if (type === vscode.FileType.File && (name.endsWith(ext.service) || name.endsWith(ext.contextService))) {
                    try {
                        const fileUri = vscode.Uri.joinPath(folderUri, name);
                        console.log(`QIP Explorer: Found service file: ${name}`);
                        const serviceData = await ContentParser.parseContentFromFile(fileUri);

                        if (serviceData) {
                            // Format: ${name}-${protocol}-${uuid}
                            const displayName = serviceData.name || serviceData.id;
                            const protocol = serviceData.content?.protocol || 'Unknown';
                            const serviceType =
                                serviceData.content?.integrationSystemType ||
                                (name.endsWith(ext.contextService)
                                    ? "CONTEXT"
                                    : "Unknown");
                            const label = `${displayName}${serviceType === "CONTEXT" ? "" : "-" + protocol}-${serviceData.id}`;

                            // Choose icon based on service type
                            let iconName = 'server';
                            switch (serviceType) {
                                case 'EXTERNAL':
                                    iconName = 'globe';
                                    break;
                                case 'INTERNAL':
                                    iconName = 'home';
                                    break;
                                case 'IMPLEMENTED':
                                    iconName = 'tools';
                                    break;
                                default:
                                    iconName = 'server';
                            }

                            const serviceItem: QipExplorerItem = {
                                id: serviceData.id,
                                label: label,
                                description: `${serviceType} service`,
                                iconPath: new vscode.ThemeIcon(iconName),
                                contextValue: 'qip-service',
                                collapsibleState: vscode.TreeItemCollapsibleState.None,
                                type: 'service',
                                fileUri: fileUri
                            };
                            services.push(serviceItem);
                            console.log(`QIP Explorer: Added service: ${label}`);
                        }
                    } catch (error) {
                        console.error(`Failed to parse service file ${name}:`, error);
                    }
                } else if (type === vscode.FileType.Directory) {
                    // Recursively search in subdirectories
                    const subFolderUri = vscode.Uri.joinPath(folderUri, name);
                    await this.findServiceFilesRecursively(subFolderUri, services);
                }
            }
        } catch (error) {
            console.error(`Failed to read directory ${folderUri.fsPath}:`, error);
        }
    }

    private async getChainChildren(chainElement: QipExplorerItem): Promise<QipExplorerItem[]> {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders || workspaceFolders.length === 0) {
            return [];
        }

        const children: QipExplorerItem[] = [];

        for (const folder of workspaceFolders) {
            try {
                const fileUri = chainElement.fileUri;
                if (!fileUri) {
                    continue;
                }

                const chainData = await ContentParser.parseContentFromFile(fileUri);

                if (chainData && chainData.content && chainData.content.elements) {
                    for (const element of chainData.content.elements) {
                        const elementItem: QipExplorerItem = {
                            id: element.id,
                            label: element.name || element.id,
                            description: `${element.type} element`,
                            iconPath: new vscode.ThemeIcon('symbol-class'),
                            contextValue: 'qip-element',
                            collapsibleState: vscode.TreeItemCollapsibleState.None,
                            type: 'element'
                        };
                        children.push(elementItem);
                    }
                }
            } catch (error) {
                console.error('Failed to read chain file:', error);
            }
        }

        return children.sort((a, b) => a.label.localeCompare(b.label));
    }

}
