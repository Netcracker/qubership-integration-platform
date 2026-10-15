import * as vscode from "vscode";
import { FILE_EXTENSIONS } from "./response/file/fileExtensions";
import { readDirectory } from "./response/file/fileApiImpl";
import { ContentParser } from "./api-services/parsers/ContentParser";

export interface CipExplorerItem {
  id: string;
  label: string;
  description?: string;
  iconPath?: vscode.ThemeIcon;
  contextValue: string;
  collapsibleState: vscode.TreeItemCollapsibleState;
  children?: CipExplorerItem[];
  fileUri?: vscode.Uri;
  type: "category" | "service" | "chain" | "element";
}

let globalCipExplorerProvider: CipExplorerProvider | null = null;

export class CipExplorerProvider implements vscode.TreeDataProvider<CipExplorerItem> {
  private _onDidChangeTreeData: vscode.EventEmitter<
    CipExplorerItem | undefined | null | void
  > = new vscode.EventEmitter<CipExplorerItem | undefined | null | void>();
  readonly onDidChangeTreeData: vscode.Event<
    CipExplorerItem | undefined | null | void
  > = this._onDidChangeTreeData.event;

  constructor(private context: vscode.ExtensionContext) {
    globalCipExplorerProvider = this;
  }

  refresh(): void {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: CipExplorerItem): vscode.TreeItem {
    const treeItem = new vscode.TreeItem(
      element.label,
      element.collapsibleState,
    );

    treeItem.description = element.description;
    treeItem.iconPath = element.iconPath;
    treeItem.contextValue = element.contextValue;
    treeItem.tooltip = element.description || element.label;

    if (element.fileUri) {
      treeItem.command = {
        command: "cip.revealInExplorer",
        title: "Reveal in File Explorer",
        arguments: [element],
      };
    }

    return treeItem;
  }

  async getChildren(element?: CipExplorerItem): Promise<CipExplorerItem[]> {
    if (!element) {
      return this.getRootItems();
    }

    switch (element.type) {
      case "category":
        if (element.label === "Chains") {
          return this.getChains();
        } else if (element.label === "Services") {
          return this.getServices();
        }
        return [];
      case "service":
        return [];
      case "chain":
        return this.getChainChildren(element);
      default:
        return [];
    }
  }

  private getRootItems(): CipExplorerItem[] {
    return [
      {
        id: "chains-category",
        label: "Chains",
        iconPath: new vscode.ThemeIcon("git-branch"),
        contextValue: "cip-chains-category",
        collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
        type: "category",
      },
      {
        id: "services-category",
        label: "Services",
        iconPath: new vscode.ThemeIcon("server"),
        contextValue: "cip-services-category",
        collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
        type: "category",
      },
    ];
  }

  private async getChains(): Promise<CipExplorerItem[]> {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      console.log("CIP Explorer: No workspace folders found");
      return [];
    }
    console.log(
      `CIP Explorer: Found ${workspaceFolders.length} workspace folders`,
    );

    const chains: CipExplorerItem[] = [];

    for (const folder of workspaceFolders) {
      try {
        console.log(
          `CIP Explorer: Searching for chains in folder: ${folder.uri.fsPath}`,
        );
        await this.findChainFilesRecursively(folder.uri, chains);
      } catch (error) {
        console.error("Failed to read workspace folder:", error);
      }
    }

    console.log(`CIP Explorer: Total chains found: ${chains.length}`);
    return chains.sort((a, b) => a.label.localeCompare(b.label));
  }

  private async findChainFilesRecursively(
    folderUri: vscode.Uri,
    chains: CipExplorerItem[],
  ): Promise<void> {
    try {
      const entries = await readDirectory(folderUri);

      const ext = FILE_EXTENSIONS;
      for (const [name, type] of entries) {
        if (type === vscode.FileType.File && name.endsWith(ext.chain)) {
          try {
            const fileUri = vscode.Uri.joinPath(folderUri, name);
            console.log(`CIP Explorer: Found chain file: ${name}`);
            const chainData = await ContentParser.parseContentFromFile(fileUri);

            if (chainData && chainData.content) {
              const elementsCount = chainData.content.elements?.length || 0;
              const connectionsCount =
                chainData.content.dependencies?.length || 0;

              // Format: ${name}-${uuid}
              const displayName = chainData.name || chainData.id;
              const label = `${displayName}-${chainData.id}`;

              const chainItem: CipExplorerItem = {
                id: chainData.id,
                label: label,
                description: `${elementsCount} elements, ${connectionsCount} connections`,
                iconPath: new vscode.ThemeIcon("git-branch"),
                contextValue: "cip-chain",
                collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
                type: "chain",
                fileUri: fileUri,
              };
              chains.push(chainItem);
              console.log(`CIP Explorer: Added chain: ${label}`);
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

  private async getServices(): Promise<CipExplorerItem[]> {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      console.log("CIP Explorer: No workspace folders found for services");
      return [];
    }
    console.log(
      `CIP Explorer: Searching for services in ${workspaceFolders.length} workspace folders`,
    );

    const services: CipExplorerItem[] = [];

    for (const folder of workspaceFolders) {
      try {
        console.log(
          `CIP Explorer: Searching for services in folder: ${folder.uri.fsPath}`,
        );
        await this.findServiceFilesRecursively(folder.uri, services);
      } catch (error) {
        console.error("Failed to read workspace folder:", error);
      }
    }

    console.log(`CIP Explorer: Total services found: ${services.length}`);
    return services.sort((a, b) => a.label.localeCompare(b.label));
  }

  private async findServiceFilesRecursively(
    folderUri: vscode.Uri,
    services: CipExplorerItem[],
  ): Promise<void> {
    try {
      const entries = await readDirectory(folderUri);

      const ext = FILE_EXTENSIONS;
      for (const [name, type] of entries) {
        if (
          type === vscode.FileType.File &&
          (name.endsWith(ext.service) ||
            name.endsWith(ext.contextService) ||
            name.endsWith(ext.mcpService))
        ) {
          try {
            const fileUri = vscode.Uri.joinPath(folderUri, name);
            console.log(`CIP Explorer: Found service file: ${name}`);
            const serviceData =
              await ContentParser.parseContentFromFile(fileUri);

            if (serviceData) {
              // Format: ${name}-${protocol}-${uuid}
              const displayName = serviceData.name || serviceData.id;
              const protocol = serviceData.content?.protocol || "Unknown";
              const serviceType =
                serviceData.content?.integrationSystemType ||
                (name.endsWith(ext.contextService)
                  ? "CONTEXT"
                  : name.endsWith(ext.mcpService)
                    ? "MCP"
                    : "Unknown");
              const label = `${displayName}${
                serviceType === "CONTEXT" || serviceType === "MCP"
                  ? ""
                  : `-${protocol}`
              }-${serviceData.id}`;

              // Choose icon based on service type
              let iconName = "server";
              switch (serviceType) {
                case "EXTERNAL":
                  iconName = "globe";
                  break;
                case "INTERNAL":
                  iconName = "home";
                  break;
                case "IMPLEMENTED":
                  iconName = "tools";
                  break;
                case "CONTEXT":
                  iconName = "server";
                  break;
                case "MCP":
                  iconName = "comment-discussion";
                  break;
              }

              const serviceItem: CipExplorerItem = {
                id: serviceData.id,
                label: label,
                description: `${serviceType} service`,
                iconPath: new vscode.ThemeIcon(iconName),
                contextValue: "cip-service",
                collapsibleState: vscode.TreeItemCollapsibleState.None,
                type: "service",
                fileUri: fileUri,
              };
              services.push(serviceItem);
              console.log(`CIP Explorer: Added service: ${label}`);
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

  private async getChainChildren(
    chainElement: CipExplorerItem,
  ): Promise<CipExplorerItem[]> {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      return [];
    }

    const children: CipExplorerItem[] = [];

    for (const folder of workspaceFolders) {
      try {
        const fileUri = chainElement.fileUri;
        if (!fileUri) {
          continue;
        }

        const chainData = await ContentParser.parseContentFromFile(fileUri);

        if (chainData && chainData.content && chainData.content.elements) {
          for (const element of chainData.content.elements) {
            const elementItem: CipExplorerItem = {
              id: element.id,
              label: element.name || element.id,
              description: `${element.type} element`,
              iconPath: new vscode.ThemeIcon("symbol-class"),
              contextValue: "cip-element",
              collapsibleState: vscode.TreeItemCollapsibleState.None,
              type: "element",
            };
            children.push(elementItem);
          }
        }
      } catch (error) {
        console.error("Failed to read chain file:", error);
      }
    }

    return children.sort((a, b) => a.label.localeCompare(b.label));
  }
}
