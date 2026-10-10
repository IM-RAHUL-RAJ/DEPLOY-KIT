// Lists every source file under src/app with its line coverage from the last
// `npm run test:coverage`, including files no spec loaded at all. Karma only
// reports files a spec imports, so its own summary overstates coverage when a
// component has no spec; this report shows those as 0% and folds them into the
// totals.
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const root = process.cwd();
const covFile = join(root, 'coverage', 'trading-app-ui', 'coverage-final.json');
if (!existsSync(covFile)) {
  console.error('No coverage-final.json: run `npm run test:coverage` first.');
  process.exit(1);
}
const coverage = JSON.parse(readFileSync(covFile, 'utf8'));
const byPath = new Map(
  Object.entries(coverage).map(([file, data]) => [relative(root, file).split(sep).join('/'), data]),
);

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) return walk(full);
    return [full];
  });
}

const sources = walk(join(root, 'src', 'app'))
  .map((f) => relative(root, f).split(sep).join('/'))
  .filter((f) => f.endsWith('.ts') && !f.endsWith('.spec.ts') && !f.includes('/testing/'));

let covered = 0;
let total = 0;
const rows = [];
for (const file of sources) {
  const data = byPath.get(file);
  let hit = 0;
  let count = 0;
  if (data) {
    const counts = Object.values(data.s);
    count = counts.length;
    hit = counts.filter((n) => n > 0).length;
  } else {
    // Not loaded by any spec: count its statements roughly as its code lines.
    count = readFileSync(join(root, file), 'utf8')
      .split('\n')
      .filter((l) => l.trim() && !l.trim().startsWith('//') && !l.trim().startsWith('*') && !l.trim().startsWith('/*') && !l.trim().startsWith('import ')).length;
  }
  covered += hit;
  total += count;
  rows.push({ file, pct: count ? (hit / count) * 100 : 100, loaded: !!data });
}

rows.sort((a, b) => a.pct - b.pct);
for (const r of rows) {
  console.log(`${r.loaded ? r.pct.toFixed(1).padStart(6) + '%' : ' NOT LOADED'}  ${r.file}`);
}
console.log(`\nStatements over ALL of src/app: ${((covered / total) * 100).toFixed(2)}% (${covered}/${total})`);
