import { SchemaResolver } from "./src/main/scripts/SchemaResolver";
import { series } from "gulp";
import { deleteAsync } from 'del';

export function clean() {
    return deleteAsync(["assets/**/*"]);
}

export function assembly(done: any) {
    const resolver = new SchemaResolver();
    resolver.resolveAllSchemas();
    done();
}

export const build = series(clean, assembly);
export default build;
