import type { APIRequestContext } from "@playwright/test";
import type { Catalog } from "../support/catalog.js";
import type { Sessions } from "../support/sessions.js";

export interface CatalogSuiteFixtures {
  catalog: Catalog;
  folder: { id: string; name: string };
  run: string;
}

export interface EchoChain {
  id: string;
  url: string;
  marker: string;
  markerHeader: string;
  elementNames: string[];
}

export interface EchoSuiteFixtures {
  echo: EchoChain;
  request: APIRequestContext;
  sessions: Sessions;
}
