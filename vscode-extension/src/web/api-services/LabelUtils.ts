export type EntityLabel = {
    name: string;
    technical: boolean;
};

/**
 * Utility class for working with labels
 */
export class LabelUtils {
    /**
     * Add a label to the labels array if it doesn't exist
     */
    static addLabel(labels: string[], label: string): string[] {
        if (!labels.includes(label)) {
            return [...labels, label];
        }
        return labels;
    }

    /**
     * Remove a label from the labels array
     */
    static removeLabel(labels: string[], label: string): string[] {
        return labels.filter(l => l !== label);
    }

    /**
     * Check if a label exists in the labels array
     */
    static hasLabel(labels: string[], label: string): boolean {
        return labels.includes(label);
    }

    /**
     * Toggle a label (add if not exists, remove if exists)
     */
    static toggleLabel(labels: string[], label: string): string[] {
        if (this.hasLabel(labels, label)) {
            return this.removeLabel(labels, label);
        } else {
            return this.addLabel(labels, label);
        }
    }

    /**
     * Get labels that match a pattern
     */
    static filterLabels(labels: string[], pattern: string): string[] {
        return labels.filter(label => label.includes(pattern));
    }

    /**
     * Sort labels alphabetically
     */
    static sortLabels(labels: string[]): string[] {
        return [...labels].sort();
    }

    /**
     * Get unique labels from multiple arrays
     */
    static mergeLabels(...labelArrays: string[][]): string[] {
        const allLabels = labelArrays.flat();
        return [...new Set(allLabels)];
    }

    /**
     * Convert string array to EntityLabel array for UI
     */
    static toEntityLabels(labels: string[]): EntityLabel[] {
        return labels.map(name => ({ name, technical: false }));
    }

    /**
     * Convert EntityLabel array to string array for backend
     */
    static fromEntityLabels(entityLabels: EntityLabel[]): string[] {
        return entityLabels.map(label => label.name);
    }

    /**
     * Add a label to EntityLabel array
     */
    static addEntityLabel(entityLabels: EntityLabel[], labelName: string): EntityLabel[] {
        if (!entityLabels.some(label => label.name === labelName)) {
            return [...entityLabels, { name: labelName, technical: false }];
        }
        return entityLabels;
    }

    /**
     * Remove a label from EntityLabel array
     */
    static removeEntityLabel(entityLabels: EntityLabel[], labelName: string): EntityLabel[] {
        return entityLabels.filter(label => label.name !== labelName);
    }

    /**
     * Check if a label exists in EntityLabel array
     */
    static hasEntityLabel(entityLabels: EntityLabel[], labelName: string): boolean {
        return entityLabels.some(label => label.name === labelName);
    }
}
