import { expect, type APIResponse } from "@playwright/test";

/** Asserts that `POST /v1/import/system` answered 200 with one `CREATED` row per service, and no other row. */
export async function expectImportedServices(
  response: APIResponse,
  services: Array<{ id: string; name?: string }>,
  message?: string,
): Promise<void> {
  expect(response.status(), await response.text()).toBe(200);
  const byId = (a: { id: string }, b: { id: string }) => a.id.localeCompare(b.id);
  const rows = ((await response.json()) as Array<{ id: string }>).sort(byId);
  expect(rows, message).toEqual([...services].sort(byId).map((each) => expect.objectContaining({ ...each, status: "CREATED" })));
}
