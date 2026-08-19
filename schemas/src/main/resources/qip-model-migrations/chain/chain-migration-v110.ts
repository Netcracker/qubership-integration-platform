interface Chain {
    id: string;
    $schema: string;
    name: string;
    metaInfo: {
        group: string;
    }
    content: {
        folder?: {}
        migrations?: string
    }
}

export function migrate(source: Chain): Chain {
    // We can check that migrations end with <108.
    const group = folderToGroup(source.content.folder);

    const target: Chain = {
        id: source.id,
        $schema: source.$schema + "/v110",
        name: source.name,
        metaInfo: {
            group: group
        },
        content: source.content
    }

    delete target.content.folder;
    delete target.content.migrations;

    return target;
}

function folderToGroup(current: any) {
    const segments: string[] = [];

    while (current) {
        const name = current.name;

        if (typeof name === "string" && name.trim() !== "") {
            segments.push(sanitizeSegment(name));
        }

        current = current.subfolder;
    }

    return segments.join("/");
}

export function sanitizeSegment(name: string | null | undefined): string {
    const FORBIDDEN_SEGMENT_CHARS = /[/:*?"<>|,;\\]/g;
    return name == null ? "" : name.replace(FORBIDDEN_SEGMENT_CHARS, "-");
}
