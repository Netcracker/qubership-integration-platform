import { ExtensionContext, Uri } from "vscode";

export function extractEntityId(path: string): string {
    const uuidRegex = /[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/i;
    const match = path.match(uuidRegex);

    if (match) {
        return match[0];
    } else {
        throw new Error(`Unable to extract entity id from path: ${path}`);
    }
}

const NAVIGATION_STATE = 'navigationState';

export function initNavigationState(context: ExtensionContext) {
    context.workspaceState.update(NAVIGATION_STATE, {});
}

export function getNavigationState(context: ExtensionContext): Record<string, string> {
    return context.workspaceState.get<Record<string, string>>(NAVIGATION_STATE)!;
}

export function getNavigationStateValue(context: ExtensionContext, documentUri: Uri): string | undefined {
    return getNavigationState(context)[documentUri.path];
}

export async function getAndClearNavigationStateValue(context: ExtensionContext, documentUri: Uri): Promise<string | undefined> {
    const navigationState = getNavigationState(context);
    const result = navigationState[documentUri.path];
    delete navigationState[documentUri.path];
    await context.workspaceState.update(NAVIGATION_STATE, navigationState);
    return result;
}

export async function updateNavigationStateValue(context: ExtensionContext, documentUri: Uri, navigationPath: string) {
    const currentState = getNavigationState(context);
    currentState[documentUri.path] = navigationPath;
    await context.workspaceState.update(NAVIGATION_STATE, currentState);
}
