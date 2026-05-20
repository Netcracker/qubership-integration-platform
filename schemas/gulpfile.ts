import { SchemaResolver } from "./src/main/scripts/schemaResolver";
import { series } from "gulp";
import { deleteAsync } from "del";
import { generateTypes } from "./src/main/scripts/generateTypes";

export async function clean() {
  await deleteAsync(["assets/**/*"]);
  await deleteAsync(["types/**/*"]);
}

export async function assembly() {
  await clean();
  const resolver = new SchemaResolver();
  await resolver.resolveAllSchemas();
  await generateTypes();
}

export const build = series(clean, assembly);
