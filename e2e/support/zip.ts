/**
 * Reading an archive the platform produced, in the two shapes every export assertion needs, and
 * building one for an import.
 *
 * Six specs opened a zip and four of them wrote the same reduction. The entry listing is the more
 * interesting of the two: the exporter builds `chains/<id>/` and `services/<id>/` unconditionally,
 * an archive whose entries sit at the zip root imports as 204 with an empty body, and a basename
 * assertion is green over exactly that failure — so the listing keeps the full path and drops the
 * directory entries, which JSZip reports alongside the files.
 *
 * Reading one entry comes in two policies and they are named apart rather than picked per caller.
 * `entryText` throws and names what the archive does hold, which is what a case asserting over a
 * known entry wants; `optionalEntryText` answers `undefined`, which is what a case asserting that
 * an entry is *present* needs, since a throw there reports the absence as an error instead of as
 * the assertion the case was written to make.
 *
 * `archiveOf` is the third export and is not a shape: it is the read that turns a response into the
 * buffer the other two take, and it refuses a body that is not a zip before JSZip can.
 */
import { expect } from "@playwright/test";
import JSZip from "jszip";
import type { APIResponse } from "@playwright/test";

/**
 * A response body as an archive, refused on the magic number rather than inside JSZip.
 *
 * A JSON error body would otherwise reach `loadAsync` and fail with a message about the zip rather
 * than about the response, which is the wrong end of the failure to read.
 */
export async function archiveOf(response: APIResponse): Promise<Buffer> {
  const body = Buffer.from(await response.body());
  expect(body.subarray(0, 2).toString("latin1"), "the body is a zip").toBe("PK");
  return body;
}

/** Every non-directory entry, full path, sorted. */
export async function entryNames(archive: Buffer): Promise<string[]> {
  const zip = await JSZip.loadAsync(archive);
  return Object.values(zip.files)
    .filter((file) => !file.dir)
    .map((file) => file.name)
    .sort();
}

/** One entry as text, or a failure naming every entry the archive does hold. */
export async function entryText(archive: Buffer, entry: string): Promise<string> {
  const zip = await JSZip.loadAsync(archive);
  const file = zip.file(entry);
  if (!file) throw new Error(`${entry} is not in the archive: ${Object.keys(zip.files).join(", ")}`);
  return await file.async("string");
}

/** An archive holding each entry, by full path, with its text. */
export async function zipOf(entries: Iterable<readonly [string, string]>): Promise<Buffer> {
  const zip = new JSZip();
  for (const [name, text] of entries) zip.file(name, text);
  return await zip.generateAsync({ type: "nodebuffer" });
}

/** The same read, answering `undefined` for an entry the archive does not hold. */
export async function optionalEntryText(
  archive: Buffer,
  entry: string,
): Promise<string | undefined> {
  const zip = await JSZip.loadAsync(archive);
  return await zip.file(entry)?.async("string");
}
