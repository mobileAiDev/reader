import fs from 'node:fs';
import path from 'node:path';

const repoRoot = process.cwd();
const args = parseArgs(process.argv.slice(2));
const rawDir = path.resolve(repoRoot, args.rawDir ?? 'build/source-quality/raw');
const localSource = path.resolve(args.localSource ?? 'C:/Users/ldp/Downloads/shuyuan.json');
const timeoutMs = Number.parseInt(args.timeoutMs ?? '30000', 10);

const yckceoIds = [
  1128, 1011, 1104, 1098,
  1129, 1127, 1126, 1125, 1123, 1122, 1121, 1118, 1117, 1115, 1114,
  1113, 1112, 1111, 1109, 1110, 1047, 1108, 1087, 835, 721, 885, 1013, 1052,
].sort((a, b) => a - b);

fs.mkdirSync(rawDir, { recursive: true });

const rows = [];
if (fs.existsSync(localSource)) {
  const output = path.join(rawDir, 'local-shuyuan.json');
  fs.copyFileSync(localSource, output);
  rows.push({ input: localSource, output, bytes: fs.statSync(output).size, status: 'copied' });
} else {
  rows.push({ input: localSource, output: '', bytes: 0, status: 'missing-local' });
}

for (const id of yckceoIds) {
  const url = `https://www.yckceo.com/yuedu/shuyuans/json/id/${id}.json`;
  const output = path.join(rawDir, `yckceo-${id}.json`);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const text = await response.text();
    JSON.parse(text.replace(/^\uFEFF/, ''));
    fs.writeFileSync(output, text.endsWith('\n') ? text : `${text}\n`, 'utf8');
    rows.push({ input: url, output, bytes: fs.statSync(output).size, status: 'downloaded' });
  } catch (error) {
    rows.push({ input: url, output, bytes: 0, status: `failed: ${error.message}` });
  } finally {
    clearTimeout(timer);
  }
}

for (const row of rows) {
  console.error([
    row.status,
    row.bytes,
    path.relative(repoRoot, row.output || rawDir).replaceAll('\\', '/'),
    row.input,
  ].join('\t'));
}

const failed = rows.filter(row => row.status.startsWith('failed'));
console.error(`rawDir=${path.relative(repoRoot, rawDir).replaceAll('\\', '/')}`);
console.error(`files=${rows.filter(row => row.bytes > 0).length}`);
console.error(`failed=${failed.length}`);
if (failed.length > 0) {
  process.exitCode = 1;
}

function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) continue;
    parsed[key.slice(2)] = argv[index + 1] && !argv[index + 1].startsWith('--')
      ? argv[++index]
      : 'true';
  }
  return parsed;
}
