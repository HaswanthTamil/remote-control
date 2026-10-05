#!/usr/bin/env node
/* Build the phone web app for deployment.
 *
 * config.js never reaches the repository (it holds the one-time pairing
 * secret), and the deployed output must not depend on it either - so this
 * script inlines the values straight into the JS modules from env vars:
 *
 *   PAIR_TOKEN=<one-time pairing token, match the relay>
 *   SERVER_URL=wss://your-relay.example/     (optional; defaults to the stock relay)
 *
 * The output under ./dist has no config.js at all: the imports of "../config.js"
 * in js/auth.js, js/remote.js and js/terminal.js are replaced by the constants
 * themselves. Local development is unchanged - the source modules still read the
 * gitignored config.js file.
 *
 * Vercel runs this via the buildCommand in vercel.json and serves ./dist.
 */
"use strict";

const { cpSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } = require("node:fs");
const { join } = require("node:path");

const dist = join(__dirname, "dist");
const pairToken = process.env.PAIR_TOKEN || "";
const serverUrl = (process.env.SERVER_URL || "wss://remote-control-lmxu.vercel.app/").trim();

const INLINE_MAIN = [
  "import { PAIR_TOKEN, SERVER_URL } from \"../config.js\";",
  "const SERVER_URL = " + JSON.stringify(serverUrl) + ";",
  "const PAIR_TOKEN = " + JSON.stringify(pairToken) + ";",
];

const INLINE_AUTH = [
  "import {",
  "  PRIVATE_KEY_STORAGE,",
  "  PUBLIC_KEY_STORAGE,",
  "  PAIRED_STORAGE,",
  "} from \"../config.js\";",
  "const PRIVATE_KEY_STORAGE = \"rc:phone:private_key\";",
  "const PUBLIC_KEY_STORAGE = \"rc:phone:public_key\";",
  "const PAIRED_STORAGE = \"rc:phone:paired\";",
];

if (!pairToken) {
  console.warn("warning: PAIR_TOKEN is empty - the relay will reject new devices");
}

function inline(file, replacement) {
  const path = join(dist, "js", file);
  let source = readFileSync(path, "utf8");
  for (const needle of replacement.imports) {
    if (!source.includes(needle)) {
      throw new Error(`build: expected import not found in ${file}: ${needle}`);
    }
  }
  source = source.replace(replacement.imports.join("\n"), replacement.code.join("\n"));
  writeFileSync(path, source);
}

rmSync(dist, { recursive: true, force: true });
mkdirSync(join(dist, "js"), { recursive: true });
mkdirSync(join(dist, "css"), { recursive: true });
for (const file of ["index.html", "remote.html", "terminal.html"]) {
  cpSync(join(__dirname, file), join(dist, file));
}
cpSync(join(__dirname, "js"), join(dist, "js"), { recursive: true });
cpSync(join(__dirname, "css"), join(dist, "css"), { recursive: true });

for (const file of readdirSync(join(dist, "js"))) {
  const path = join(dist, "js", file);
  console.log(`built js/${file}`);
}
console.log("inlining config from env into js/auth.js, js/remote.js, js/terminal.js");
inline("auth.js", { imports: INLINE_AUTH.slice(0, 5), code: INLINE_AUTH.slice(5) });
inline("remote.js", { imports: INLINE_MAIN.slice(0, 1), code: INLINE_MAIN.slice(1) });
inline("terminal.js", { imports: INLINE_MAIN.slice(0, 1), code: INLINE_MAIN.slice(1) });

console.log(`done: ${pairToken ? "PAIR_TOKEN set" : "PAIR_TOKEN EMPTY"}, SERVER_URL=${serverUrl}, no config.js emitted`);