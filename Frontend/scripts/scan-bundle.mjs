// Searches the built bundle for anything that must never reach a browser.
//
// Run after `npm run build`. Exits non-zero on any hit, so it can gate a review or
// a pipeline. It checks the brief's patterns, any three-part JWT literal, and the
// literal values of the team's own secrets read from the root .env (if present),
// which catches a key pasted in under a name none of the patterns would match.
// Matches are reported by file and pattern; secret values are never printed.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(frontend, 'dist');

const PATTERNS = [
  ['x-api-key', /x-api-key/i],
  ['api_key', /api_key/i],
  ['api-key', /api-key/i],
  ['fauxnance', /fauxnance/i],
  ['execute-api host', /execute-api\.[a-z0-9-]+\.amazonaws\.com/i],
  ['jwt_secret', /jwt_secret/i],
  ['JWT literal', /eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/],
];

/** Values from the root .env whose names mark them as secrets. */
function secretValues() {
  const values = [];
  for (const file of [join(frontend, '..', '.env'), ...serviceEnvFiles()]) {
    if (!existsSync(file)) continue;
    for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
      const match = /^\s*([A-Z0-9_]*(SECRET|KEY|PASSWORD|PASS)[A-Z0-9_]*)\s*=\s*(.+?)\s*$/.exec(line);
      if (match && match[3].length >= 8 && !match[3].startsWith('replace')) {
        values.push([match[1], match[3].replace(/^['"]|['"]$/g, '')]);
      }
    }
  }
  return values;
}

function serviceEnvFiles() {
  const services = join(frontend, '..', 'Services');
  if (!existsSync(services)) return [];
  return readdirSync(services).map((name) => join(services, name, '.env'));
}

function* files(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) yield* files(path);
    else yield path;
  }
}

if (!existsSync(dist)) {
  console.error('No dist/ folder. Run `npm run build` first.');
  process.exit(2);
}

const secrets = secretValues();
const hits = [];
let scanned = 0;
for (const file of files(dist)) {
  const text = readFileSync(file, 'utf8');
  scanned += 1;
  for (const [label, pattern] of PATTERNS) {
    if (pattern.test(text)) hits.push(`${relative(frontend, file)}: ${label}`);
  }
  for (const [name, value] of secrets) {
    if (text.includes(value)) hits.push(`${relative(frontend, file)}: value of ${name}`);
  }
}

if (hits.length) {
  console.error(`Bundle scan FAILED (${hits.length} hit(s)):`);
  for (const hit of hits) console.error(`  ${hit}`);
  process.exit(1);
}
console.log(`Bundle scan clean: ${scanned} file(s), ${PATTERNS.length} pattern(s), ${secrets.length} secret value(s) checked.`);
