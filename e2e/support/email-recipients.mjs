/**
 * The recipients of the run email, from the comma-separated list in `EMAIL_TO`. `_e2e-kind.yaml`
 * runs it before the suite, so that no later step header or log line shows an address:
 *
 *     EMAIL_TO="a@example.com, b@example.com" node support/email-recipients.mjs
 *
 * It prints an `::add-mask::` command for every entry, then exits 1 on an entry that is not an
 * address. Otherwise it appends `to=<addresses>` to `$GITHUB_OUTPUT`, with an empty value for an
 * empty list, which sends no email.
 */
import fs from "node:fs";
import { fileURLToPath } from "node:url";

/** One `@`, no whitespace, and a dot inside the domain. */
const ADDRESS = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/**
 * The entries of a comma-separated list, trimmed, without the empty ones.
 *
 * @param {string | undefined} list
 * @returns {string[]}
 */
export function entries(list) {
  return (list ?? "")
    .split(",")
    .map((each) => each.trim())
    .filter(Boolean);
}

/**
 * The addresses of `list`, and an error naming the position of the first entry that is not one.
 * The message leaves the entry out, because the log is public.
 *
 * @param {string | undefined} list
 * @returns {string[]}
 */
export function recipients(list) {
  const all = entries(list);
  const bad = all.findIndex((each) => !ADDRESS.test(each));
  if (bad >= 0) {
    throw new Error(`Recipient ${bad + 1} of ${all.length} is not an email address: an address has one @, no spaces, and a dot in its domain.`);
  }
  return all;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const list = process.env.EMAIL_TO;
  for (const each of entries(list)) console.log(`::add-mask::${each}`);
  try {
    const to = recipients(list);
    if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `to=${to.join(",")}\n`);
    console.log(to.length ? `The run email goes to ${to.length} ${to.length === 1 ? "address" : "addresses"}.` : "The run email is not sent.");
  } catch (error) {
    console.log(`::error::${/** @type {Error} */ (error).message}`);
    process.exit(1);
  }
}
