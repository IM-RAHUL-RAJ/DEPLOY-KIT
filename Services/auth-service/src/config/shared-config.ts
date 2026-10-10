import { existsSync, readFileSync } from 'fs';
import { join } from 'path';

/**
 * Reads the platform's shared Config/application.yml: hosts and ports every service
 * agrees on. Only the plain `key: value` mappings that file uses are understood -
 * nesting by indentation, comments, no lists - so no YAML library is needed. Keys come
 * back flattened, e.g. `services.auth.port`. A missing file is an empty map.
 */
export function parseSharedConfig(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  const stack: { indent: number; key: string }[] = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/\s+#.*$/, '');
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const m = line.match(/^(\s*)([A-Za-z0-9_.-]+):\s*(.*)$/);
    if (!m) continue;
    const indent = m[1].length;
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    const path = [...stack.map((s) => s.key), m[2]].join('.');
    const value = m[3].trim().replace(/^["']|["']$/g, '');
    if (value) {
      out[path] = value;
    } else {
      stack.push({ indent, key: m[2] });
    }
  }
  return out;
}

const CANDIDATES = ['Config/application.yml', '../Config/application.yml', '../../Config/application.yml'];

export function loadSharedConfig(cwd = process.cwd(), env = process.env): Record<string, string> {
  const files = env.SHARED_CONFIG ? [env.SHARED_CONFIG, ...CANDIDATES] : CANDIDATES;
  for (const file of files) {
    const path = join(cwd, file);
    if (existsSync(path)) {
      return parseSharedConfig(readFileSync(path, 'utf8'));
    }
  }
  return {};
}
