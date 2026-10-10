// Reads the errorCode enum out of each contract and writes the combined list to
// src/app/core/services/contract-error-codes.json.
//
// The error-catalogue spec checks every code in that file against the UI's
// message mapping, so a code added to a contract without a message fails the
// unit tests rather than reaching a trader as a blank panel. Runs before every
// `npm test` and every `npm run generate`.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..');
const contracts = join(frontend, '..', 'Contracts', 'API Schemas');
const output = join(frontend, 'src', 'app', 'core', 'services', 'contract-error-codes.json');

/** The enum under the first `errorCode:` property, in flow style: [A, B, ...]. */
function errorCodes(file) {
  const text = readFileSync(join(contracts, file), 'utf8');
  const property = text.indexOf('errorCode:');
  if (property < 0) throw new Error(`${file}: no errorCode property`);
  const enumAt = text.indexOf('enum:', property);
  const open = text.indexOf('[', enumAt);
  const close = text.indexOf(']', open);
  if (enumAt < 0 || open < 0 || close < 0) throw new Error(`${file}: errorCode has no enum`);
  return text
    .slice(open + 1, close)
    .split(',')
    .map((code) => code.trim().replace(/^['"]|['"]$/g, ''))
    .filter(Boolean);
}

const codes = {
  'trade-api.yaml': errorCodes('trade-api.yaml'),
  'auth-api.yaml': errorCodes('auth-api.yaml'),
};
writeFileSync(output, JSON.stringify(codes, null, 2) + '\n');
console.log(`error codes: trade=${codes['trade-api.yaml'].length} auth=${codes['auth-api.yaml'].length}`);
