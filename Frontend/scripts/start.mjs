// `npm start`: writes the API addresses from Config/.env (sync-config.mjs), then runs the
// dev server on the frontend port from the same place: FRONTEND_PORT, else
// frontend.port in Application/Config/application.yml.
import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseDotEnv, parseSharedConfig } from './sync-config.mjs';

const frontend = join(dirname(fileURLToPath(import.meta.url)), '..');
const application = join(frontend, '..');
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : '');

const port =
  process.env.FRONTEND_PORT ||
  parseDotEnv(read(join(application, '.env'))).FRONTEND_PORT ||
  parseSharedConfig(read(process.env.SHARED_CONFIG || join(application, 'Config', 'application.yml')))['frontend.port'];
if (!port) {
  console.error('FRONTEND_PORT is not set and Config/application.yml has no frontend.port.');
  process.exit(1);
}

const sync = spawn(process.execPath, [join(frontend, 'scripts', 'sync-config.mjs'), '--print'], { stdio: 'inherit' });
sync.on('exit', (code) => {
  if (code !== 0) process.exit(code ?? 1);
  const ng = spawn('npx', ['ng', 'serve', '--port', String(port), ...process.argv.slice(2)], {
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  ng.on('exit', (c) => process.exit(c ?? 0));
});
