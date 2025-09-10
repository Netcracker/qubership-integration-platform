import { SchemaResolver } from "./src/main/scripts/SchemaResolver";
import { series } from "gulp";
import { deleteAsync } from "del";

export function clean() {
    return deleteAsync(["assets/**/*"]);
}

export async function assembly() {
    await clean();
    const resolver = new SchemaResolver();
    await resolver.resolveAllSchemas();
}

export const build = series(clean, assembly);
