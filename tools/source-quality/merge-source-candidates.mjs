import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = process.cwd();
const args = parseArgs(process.argv.slice(2));
const rawDir = path.resolve(repoRoot, args.rawDir ?? 'build/source-quality/raw');
const appSourcePath = path.resolve(repoRoot, args.appSource ?? 'app/src/main/assets/source-engine/book-sources.json');
const outputPath = path.resolve(repoRoot, args.output ?? 'tools/source-quality/candidates/source-candidates-deduped.json');
const reportPath = path.resolve(repoRoot, args.report ?? 'build/source-quality/source-candidates-report.tsv');
const includeApp = args.includeApp !== 'false';

const inputFiles = [];
if (includeApp) {
  inputFiles.push({ label: 'embedded', file: appSourcePath });
}
if (fs.existsSync(rawDir)) {
  for (const file of fs.readdirSync(rawDir).filter(name => name.endsWith('.json')).sort()) {
    inputFiles.push({ label: path.basename(file, '.json'), file: path.join(rawDir, file) });
  }
}

const rows = [];
const byBaseUrl = new Map();
for (const input of inputFiles) {
  const payload = readJsonArray(input.file);
  rows.push({
    input: input.label,
    file: path.relative(repoRoot, input.file).replaceAll('\\', '/'),
    count: payload.length,
    sha256: sha256(input.file),
  });
  payload.forEach((source, index) => {
    const sourceUrl = String(source.bookSourceUrl ?? '').trim();
    const baseUrl = normalizeBaseUrl(sourceUrl);
    if (!baseUrl) return;
    const candidate = {
      input: input.label,
      index,
      baseUrl,
      sourceUrl,
      sourceName: String(source.bookSourceName ?? '').trim(),
      structuralScore: structuralScore(source),
      source,
    };
    const current = byBaseUrl.get(baseUrl);
    if (!current || compareCandidate(candidate, current) < 0) {
      byBaseUrl.set(baseUrl, candidate);
    }
  });
}

const winners = [...byBaseUrl.values()].sort((a, b) =>
  b.structuralScore - a.structuralScore ||
  a.baseUrl.localeCompare(b.baseUrl, 'en')
);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(winners.map(item => item.source), null, 2)}\n`, 'utf8');

const duplicateStats = countDuplicates(inputFiles);
const report = [
  [
    'kind',
    'input',
    'file',
    'count',
    'uniqueBaseUrl',
    'duplicateBaseUrl',
    'sha256',
    'note',
  ].join('\t'),
  ...rows.map(row => [
    'input',
    row.input,
    row.file,
    row.count,
    '',
    '',
    row.sha256,
    '',
  ].join('\t')),
  [
    'summary',
    'merged',
    path.relative(repoRoot, outputPath).replaceAll('\\', '/'),
    rows.reduce((sum, row) => sum + row.count, 0),
    winners.length,
    duplicateStats.duplicateCount,
    '',
    `includeApp=${includeApp}`,
  ].join('\t'),
  '',
  [
    'kind',
    'baseUrl',
    'winnerInput',
    'winnerName',
    'winnerSourceUrl',
    'structuralScore',
    'duplicateCount',
    'duplicateInputs',
  ].join('\t'),
  ...duplicateStats.items.slice(0, 2000).map(item => [
    'baseUrl',
    item.baseUrl,
    item.winner.input,
    clean(item.winner.sourceName),
    clean(item.winner.sourceUrl),
    item.winner.structuralScore,
    item.count,
    clean(item.inputs.join('|')),
  ].join('\t')),
];
fs.writeFileSync(reportPath, `${report.join('\n')}\n`, 'utf8');

console.error(`inputs=${rows.length}`);
console.error(`sourceRows=${rows.reduce((sum, row) => sum + row.count, 0)}`);
console.error(`uniqueBaseUrl=${winners.length}`);
console.error(`duplicateBaseUrl=${duplicateStats.duplicateCount}`);
console.error(`output=${path.relative(repoRoot, outputPath).replaceAll('\\', '/')}`);
console.error(`report=${path.relative(repoRoot, reportPath).replaceAll('\\', '/')}`);

function readJsonArray(file) {
  const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
  const json = JSON.parse(text);
  if (Array.isArray(json)) return json;
  if (Array.isArray(json.data)) return json.data;
  if (Array.isArray(json.sources)) return json.sources;
  throw new Error(`unsupported source json shape: ${file}`);
}

function countDuplicates(files) {
  const all = new Map();
  for (const input of files) {
    for (const [index, source] of readJsonArray(input.file).entries()) {
      const baseUrl = normalizeBaseUrl(source.bookSourceUrl);
      if (!baseUrl) continue;
      const item = all.get(baseUrl) ?? { baseUrl, candidates: [] };
      item.candidates.push({
        input: input.label,
        index,
        baseUrl,
        sourceUrl: String(source.bookSourceUrl ?? '').trim(),
        sourceName: String(source.bookSourceName ?? '').trim(),
        structuralScore: structuralScore(source),
        source,
      });
      all.set(baseUrl, item);
    }
  }
  const items = [...all.values()]
    .filter(item => item.candidates.length > 1)
    .map(item => {
      const candidates = item.candidates.sort(compareCandidate);
      return {
        baseUrl: item.baseUrl,
        winner: candidates[0],
        count: candidates.length,
        inputs: [...new Set(candidates.map(candidate => candidate.input))].sort(),
      };
    })
    .sort((a, b) => b.count - a.count || a.baseUrl.localeCompare(b.baseUrl, 'en'));
  return {
    items,
    duplicateCount: items.reduce((sum, item) => sum + item.count - 1, 0),
  };
}

function normalizeBaseUrl(value) {
  let text = String(value ?? '').trim();
  if (!text) return '';
  text = text.split('##')[0].split('#')[0].trim();
  text = text.replace(/[?].*$/, '').replace(/\/+$/, '');
  try {
    const url = new URL(text);
    const pathName = url.pathname.replace(/\/+$/, '');
    return `${url.hostname.toLowerCase()}${pathName}`;
  } catch {
    return text.toLowerCase();
  }
}

function compareCandidate(a, b) {
  return b.structuralScore - a.structuralScore ||
    sourcePriority(a.input) - sourcePriority(b.input) ||
    b.sourceName.length - a.sourceName.length ||
    a.sourceUrl.localeCompare(b.sourceUrl, 'en');
}

function sourcePriority(input) {
  if (input === 'embedded') return 0;
  if (input === 'local-shuyuan') return 1;
  return 2;
}

function structuralScore(source) {
  let score = 0;
  if (source.enabled !== false) score += 100;
  if (hasText(source.searchUrl)) score += 120;
  if (hasRules(source.ruleSearch, ['bookList', 'name', 'bookUrl'])) score += 180;
  if (hasRules(source.ruleBookInfo, ['tocUrl'])) score += 40;
  if (hasRules(source.ruleToc, ['chapterList', 'chapterName', 'chapterUrl'])) score += 180;
  if (hasRules(source.ruleContent, ['content'])) score += 160;
  if (!hasUnsupportedCriticalRule(JSON.stringify([
    source.searchUrl,
    source.ruleSearch,
    source.ruleBookInfo,
    source.ruleToc,
    source.ruleContent,
  ]))) {
    score += 120;
  }
  const respondTime = Number.parseInt(String(source.respondTime ?? ''), 10);
  if (Number.isFinite(respondTime) && respondTime > 0) {
    score += Math.max(0, 120 - Math.min(120, Math.floor(respondTime / 50)));
  }
  const lastUpdateTime = Number.parseInt(String(source.lastUpdateTime ?? ''), 10);
  if (Number.isFinite(lastUpdateTime) && lastUpdateTime > 0) {
    score += Math.min(80, Math.max(0, Math.floor((lastUpdateTime - 1_600_000_000_000) / 15_000_000_000)));
  }
  return score;
}

function hasRules(ruleSet, names) {
  return names.every(name => hasText(ruleSet?.[name]));
}

function hasUnsupportedCriticalRule(value) {
  const rule = String(value ?? '').toLowerCase();
  return ['<js>', 'java.ajax', 'java.put', 'eval(', 'source.', 'cookie.', '{{eval', '{{java.put']
    .some(marker => rule.includes(marker));
}

function hasText(value) {
  return String(value ?? '').trim().length > 0;
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function clean(value) {
  return String(value ?? '').replaceAll('\t', ' ').replaceAll('\n', ' ').replaceAll('\r', ' ').trim();
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
