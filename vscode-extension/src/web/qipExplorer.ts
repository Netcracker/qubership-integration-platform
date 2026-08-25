import * as vscode from "vscode";
import {
  getExtensionsForFile,
  getSchemaUrlsForFile,
} from "./response/file/fileExtensions";
import {
  allServiceExtensions,
  isServiceFileOfAnyKind,
  resolveServiceType,
  isCurrentFormatServiceName,
} from "./response/file/serviceFileType";
import { blockingSibling } from "./response/file/lookupOutcome";
import { readDirectory } from "./response/file/fileApiImpl";
import { ContentParser } from "./api-services/parsers/ContentParser";
import { IntegrationSystemType } from "./api-services/servicesTypes";

export interface QipExplorerItem {
  id: string;
  label: string;
  description?: string;
  iconPath?: vscode.ThemeIcon;
  contextValue: string;
  collapsibleState: vscode.TreeItemCollapsibleState;
  children?: QipExplorerItem[];
  fileUri?: vscode.Uri;
  type: "category" | "service-group" | "service" | "chain" | "element";
}

/** The bucket for a service whose type neither its name nor its body states. */
const UNKNOWN_SERVICE_TYPE = "Unknown";

type ServiceGroupType = IntegrationSystemType | typeof UNKNOWN_SERVICE_TYPE;

/** The groups the services category renders, in the order they appear. */
const SERVICE_GROUPS: readonly { type: ServiceGroupType; label: string }[] = [
  { type: IntegrationSystemType.EXTERNAL, label: "External" },
  { type: IntegrationSystemType.INTERNAL, label: "Internal" },
  { type: IntegrationSystemType.IMPLEMENTED, label: "Implemented" },
  { type: IntegrationSystemType.CONTEXT, label: "Context" },
  { type: IntegrationSystemType.MCP, label: "MCP" },
  { type: UNKNOWN_SERVICE_TYPE, label: UNKNOWN_SERVICE_TYPE },
];

// The `Record` keyed by the group type makes a new service type a compile error until it gets an
// icon, and the group above it.
const SERVICE_ICONS: Record<ServiceGroupType, string> = {
  [IntegrationSystemType.EXTERNAL]: "globe",
  [IntegrationSystemType.INTERNAL]: "home",
  [IntegrationSystemType.IMPLEMENTED]: "tools",
  [IntegrationSystemType.CONTEXT]: "server",
  [IntegrationSystemType.MCP]: "comment-discussion",
  [UNKNOWN_SERVICE_TYPE]: "question",
};

/** A group reuses the icon of the services it holds. */
function serviceIconName(serviceType: ServiceGroupType): string {
  return SERVICE_ICONS[serviceType];
}

/** The group a service belongs to. An untyped service reads as `Unknown` rather than vanishing. */
function serviceGroupType(
  serviceType: IntegrationSystemType | undefined,
): ServiceGroupType {
  return serviceType ?? UNKNOWN_SERVICE_TYPE;
}

/** One service as discovery found it, kept by id so a half-converted one is not listed twice. */
interface DiscoveredService {
  item: QipExplorerItem;
  groupType: ServiceGroupType;
  statesType: boolean;
}

/** What the walk found: the services, and the files it could not read. */
interface DiscoveredServices {
  servicesById: Map<string, DiscoveredService>;
  unreadable: vscode.Uri[];
}

/**
 * The services the tree may show. A file the walk could not read is neither a service nor an
 * absence: the sibling it outranks would otherwise be listed in its place, and the tree would name
 * the superseded document as the current one — the same rule `getServices` and every lookup follow
 * (`blockingSibling` in `lookupOutcome.ts`). The tree cannot refuse, so it drops that entry
 * instead; the file stays visible in the file explorer, and every read behind the id refuses by
 * name. A file it could not read anywhere else takes nothing off the tree, and neither does one
 * a listed service outranks: a converted service is shown from its typed file whatever state the
 * legacy sibling is in.
 */
function dropUnreadableSiblings({
  servicesById,
  unreadable,
}: DiscoveredServices): Map<string, DiscoveredService> {
  if (unreadable.length === 0) {
    return servicesById;
  }
  const extensions = allServiceExtensions(getExtensionsForFile());
  const shown = new Map<string, DiscoveredService>();
  for (const [serviceId, service] of servicesById) {
    const fileUri = service.item.fileUri;
    const sibling = fileUri && blockingSibling(fileUri, unreadable, extensions);
    if (sibling) {
      console.error(
        `QIP Explorer: hiding service ${serviceId}; ${sibling.path} could not be read`,
      );
      continue;
    }
    shown.set(serviceId, service);
  }
  return shown;
}

let globalQipExplorerProvider: QipExplorerProvider | null = null;

export class QipExplorerProvider
  implements vscode.TreeDataProvider<QipExplorerItem>
{
  private _onDidChangeTreeData: vscode.EventEmitter<
    QipExplorerItem | undefined | null | void
  > = new vscode.EventEmitter<QipExplorerItem | undefined | null | void>();
  readonly onDidChangeTreeData: vscode.Event<
    QipExplorerItem | undefined | null | void
  > = this._onDidChangeTreeData.event;

  constructor(private context: vscode.ExtensionContext) {
    globalQipExplorerProvider = this;
  }

  refresh(): void {
    this._onDidChangeTreeData.fire();
  }

  getTreeItem(element: QipExplorerItem): vscode.TreeItem {
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
        command: "qip.revealInExplorer",
        title: "Reveal in File Explorer",
        arguments: [element],
      };
    }

    return treeItem;
  }

  async getChildren(element?: QipExplorerItem): Promise<QipExplorerItem[]> {
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
      case "service-group":
        return element.children ?? [];
      case "service":
        return [];
      case "chain":
        return this.getChainChildren(element);
      default:
        return [];
    }
  }

  private getRootItems(): QipExplorerItem[] {
    return [
      {
        id: "chains-category",
        label: "Chains",
        iconPath: new vscode.ThemeIcon("git-branch"),
        contextValue: "qip-chains-category",
        collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
        type: "category",
      },
      {
        id: "services-category",
        label: "Services",
        iconPath: new vscode.ThemeIcon("server"),
        contextValue: "qip-services-category",
        collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
        type: "category",
      },
    ];
  }

  private async getChains(): Promise<QipExplorerItem[]> {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      console.log("QIP Explorer: No workspace folders found");
      return [];
    }
    console.log(
      `QIP Explorer: Found ${workspaceFolders.length} workspace folders`,
    );

    const chains: QipExplorerItem[] = [];

    for (const folder of workspaceFolders) {
      try {
        console.log(
          `QIP Explorer: Searching for chains in folder: ${folder.uri.fsPath}`,
        );
        await this.findChainFilesRecursively(folder.uri, chains);
      } catch (error) {
        console.error("Failed to read workspace folder:", error);
      }
    }

    console.log(`QIP Explorer: Total chains found: ${chains.length}`);
    return chains.sort((a, b) => a.label.localeCompare(b.label));
  }

  private async findChainFilesRecursively(
    folderUri: vscode.Uri,
    chains: QipExplorerItem[],
  ): Promise<void> {
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
              const connectionsCount =
                chainData.content.dependencies?.length || 0;

              // Format: ${name}-${uuid}
              const displayName = chainData.name || chainData.id;
              const label = `${displayName}-${chainData.id}`;

              const chainItem: QipExplorerItem = {
                id: chainData.id,
                label: label,
                description: `${elementsCount} elements, ${connectionsCount} connections`,
                iconPath: new vscode.ThemeIcon("git-branch"),
                contextValue: "qip-chain",
                collapsibleState: vscode.TreeItemCollapsibleState.Collapsed,
                type: "chain",
                fileUri: fileUri,
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
      console.log("QIP Explorer: No workspace folders found for services");
      return [];
    }
    console.log(
      `QIP Explorer: Searching for services in ${workspaceFolders.length} workspace folders`,
    );

    const discovered: DiscoveredServices = {
      servicesById: new Map<string, DiscoveredService>(),
      unreadable: [],
    };

    for (const folder of workspaceFolders) {
      try {
        console.log(
          `QIP Explorer: Searching for services in folder: ${folder.uri.fsPath}`,
        );
        await this.findServiceFilesRecursively(folder.uri, discovered);
      } catch (error) {
        console.error("Failed to read workspace folder:", error);
      }
    }

    return this.buildServiceGroups(dropUnreadableSiblings(discovered));
  }

  private buildServiceGroups(
    servicesById: Map<string, DiscoveredService>,
  ): QipExplorerItem[] {
    const servicesByType = new Map<ServiceGroupType, QipExplorerItem[]>();
    for (const { item, groupType } of servicesById.values()) {
      const group = servicesByType.get(groupType) ?? [];
      group.push(item);
      servicesByType.set(groupType, group);
    }

    const groups: QipExplorerItem[] = [];

    for (const { type, label } of SERVICE_GROUPS) {
      const services = servicesByType.get(type);
      if (!services || services.length === 0) {
        continue;
      }
      groups.push({
        id: `service-group-${type}`,
        label,
        description: `${services.length} ${services.length === 1 ? "service" : "services"}`,
        iconPath: new vscode.ThemeIcon(serviceIconName(type)),
        contextValue: "qip-service-group",
        collapsibleState: vscode.TreeItemCollapsibleState.Expanded,
        type: "service-group",
        children: services.sort((a, b) => a.label.localeCompare(b.label)),
      });
    }

    console.log(
      `QIP Explorer: Total services found: ${groups.reduce((total, group) => total + (group.children?.length ?? 0), 0)}`,
    );
    return groups;
  }

  private async findServiceFilesRecursively(
    folderUri: vscode.Uri,
    discovered: DiscoveredServices,
  ): Promise<void> {
    try {
      const entries = await readDirectory(folderUri);

      for (const [name, type] of entries) {
        // Both maps come from the file's own name, not from whichever app is current: in a
        // multi-app workspace the current app's config types another app's files into Unknown.
        const ext = getExtensionsForFile(name);
        if (
          type === vscode.FileType.File &&
          isServiceFileOfAnyKind(name, ext)
        ) {
          try {
            const fileUri = vscode.Uri.joinPath(folderUri, name);
            console.log(`QIP Explorer: Found service file: ${name}`);
            const serviceData =
              await ContentParser.parseContentFromFile(fileUri);

            if (serviceData) {
              // Format: ${name}-${protocol}-${uuid}
              const displayName = serviceData.name || serviceData.id;
              const protocol = serviceData.content?.protocol || "Unknown";
              // A file that states a type in neither its $schema nor its body stays visible under
              // Unknown.
              const serviceType = serviceGroupType(
                resolveServiceType(
                  name,
                  serviceData,
                  getSchemaUrlsForFile(name),
                ),
              );
              const label = `${displayName}${
                serviceType === IntegrationSystemType.CONTEXT ||
                serviceType === IntegrationSystemType.MCP
                  ? ""
                  : `-${protocol}`
              }-${serviceData.id}`;

              const serviceItem: QipExplorerItem = {
                id: serviceData.id,
                label: label,
                description: `${serviceType} service`,
                iconPath: new vscode.ThemeIcon(serviceIconName(serviceType)),
                contextValue: "qip-service",
                collapsibleState: vscode.TreeItemCollapsibleState.None,
                type: "service",
                fileUri: fileUri,
              };
              // A half-converted service has both files on disk. List it once, from the current
              // name, the same precedence `plainServiceExtensions` and `getServices` apply.
              const statesType = isCurrentFormatServiceName(name, ext);
              const known = discovered.servicesById.get(serviceData.id);
              if (!known || (statesType && !known.statesType)) {
                discovered.servicesById.set(serviceData.id, {
                  item: serviceItem,
                  groupType: serviceType,
                  statesType,
                });
              }
              console.log(`QIP Explorer: Added service: ${label}`);
            }
          } catch (error) {
            // The file cannot be attributed to a service, and the tree must not put its sibling in
            // its place — `dropUnreadableSiblings` decides that once the walk is done.
            console.error(`Failed to parse service file ${name}:`, error);
            discovered.unreadable.push(vscode.Uri.joinPath(folderUri, name));
          }
        } else if (type === vscode.FileType.Directory) {
          // Recursively search in subdirectories
          const subFolderUri = vscode.Uri.joinPath(folderUri, name);
          await this.findServiceFilesRecursively(subFolderUri, discovered);
        }
      }
    } catch (error) {
      console.error(`Failed to read directory ${folderUri.fsPath}:`, error);
    }
  }

  private async getChainChildren(
    chainElement: QipExplorerItem,
  ): Promise<QipExplorerItem[]> {
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
              iconPath: new vscode.ThemeIcon("symbol-class"),
              contextValue: "qip-element",
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
