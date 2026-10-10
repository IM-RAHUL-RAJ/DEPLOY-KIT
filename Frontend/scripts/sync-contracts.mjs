// Copies the team's API contracts into Frontend/contracts/ before generation.
//
// The single source of truth is ../Contracts/API Schemas/. The generator reads a
// local copy because its $ref resolver cannot handle the space in that path.
// Edit the contract there, never here: this copy is overwritten on every run.
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(frontend, '..', 'Contracts', 'API Schemas');
const target = join(frontend, 'contracts');

mkdirSync(target, { recursive: true });
for (const file of ['trade-api.yaml', 'auth-api.yaml']) {
  copyFileSync(join(source, file), join(target, file));
  console.log(`synced ${file}`);
}
