import fs from 'node:fs';
import path from 'node:path';

const repoRoot = process.cwd();
const sourceJsonPath = process.argv[2] ?? path.join(repoRoot, 'build/debug/device-book-sources.json');
const probeTsvPath = process.argv[3] ?? path.join(repoRoot, 'build/tmp/probe-100-after-poyun-fix.tsv');
const outputPath = process.argv[4] ?? path.join(repoRoot, 'app/src/main/assets/source-quality-seed-v1.tsv');

const sources = JSON.parse(fs.readFileSync(sourceJsonPath, 'utf8'));
const evidence = readProbeEvidence(probeTsvPath);

const rows = [];
for (const source of sources) {
  if (!isCompatible(source)) continue;
  const scored = scoreSource(source, evidence.get(normalizeUrl(source.bookSourceUrl)));
  rows.push(scored);
}

rows.sort((a, b) =>
  a.tier - b.tier ||
  b.score - a.score ||
  a.bucket.localeCompare(b.bucket, 'zh-Hans-CN') ||
  a.sourceName.localeCompare(b.sourceName, 'zh-Hans-CN')
);

const now = new Date().toISOString();
const lines = [
  '# source-quality-seed-v1',
  `# generatedAt=${now}`,
  `# sourceJson=${path.relative(repoRoot, sourceJsonPath).replaceAll('\\', '/')}`,
  `# probeEvidence=${path.relative(repoRoot, probeTsvPath).replaceAll('\\', '/')}`,
  '# v1 stores global source baseline only. Per-book/source scores are local MMKV deltas.',
  '# regenerate after a larger probe run and commit this file with the app.',
  'kind\tsourceUrl\tsourceName\ttier\tbucket\tscore\tspeed\tcoverage\tfreshness\tquality\tstability\tnote',
  ...rows.map(row => [
    'source',
    clean(row.sourceUrl),
    clean(row.sourceName),
    row.tier,
    row.bucket,
    row.score,
    row.speedScore,
    row.coverageScore,
    row.freshnessScore,
    row.qualityScore,
    row.stabilityScore,
    clean(row.note),
  ].join('\t')),
];

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${lines.join('\n')}\n`, 'utf8');

const tierCounts = rows.reduce((acc, row) => {
  acc[row.tier] = (acc[row.tier] ?? 0) + 1;
  return acc;
}, {});
console.error(`wrote ${rows.length} source seed rows to ${outputPath}`);
console.error(`tier1=${tierCounts[1] ?? 0} tier2=${tierCounts[2] ?? 0} tier3=${tierCounts[3] ?? 0}`);

function readProbeEvidence(filePath) {
  const map = new Map();
  if (!fs.existsSync(filePath)) return map;
  const lines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/);
  const header = lines.shift()?.split('\t') ?? [];
  const index = Object.fromEntries(header.map((name, i) => [name, i]));
  for (const line of lines) {
    if (!line || line.startsWith('#')) continue;
    const parts = line.split('\t');
    const url = normalizeUrl(parts[index.bestUrl] ?? '');
    if (!url) continue;
    const item = map.get(url) ?? {
      pass: 0,
      warn: 0,
      fail: 0,
      totalCatalog: 0,
      catalogSamples: 0,
      coverOk: 0,
      contentOk: 0,
      contentBad: 0,
      books: [],
    };
    const status = parts[index.status] ?? '';
    if (status === 'PASS') item.pass += 1;
    else if (status === 'WARN') item.warn += 1;
    else if (status === 'FAIL') item.fail += 1;
    const catalog = Number.parseInt(parts[index.catalog] ?? '0', 10);
    if (Number.isFinite(catalog) && catalog > 0) {
      item.totalCatalog += catalog;
      item.catalogSamples += 1;
    }
    if ((parts[index.cover] ?? '').startsWith('ok:')) item.coverOk += 1;
    const content = parts[index.content] ?? '';
    if (content.includes(':ok:')) item.contentOk += 1;
    if (content.includes(':bad:')) item.contentBad += 1;
    item.books.push(parts[index.book] ?? '');
    map.set(url, item);
  }
  return map;
}

function scoreSource(source, evidence) {
  const label = `${source.bookSourceName ?? ''}\n${source.bookSourceUrl ?? ''}\n${source.bookSourceGroup ?? ''}`.toLowerCase();
  const bucket = bucketFor(label);
  const speedScore = speedScoreFor(source.respondTime);
  const coverageScore = evidence ? Math.min(100, Math.round((evidence.totalCatalog / Math.max(1, evidence.catalogSamples)) / 18)) : 0;
  const freshnessScore = evidence ? Math.min(100, evidence.pass * 12 + evidence.warn * 6 + evidence.fail * 2) : markerFreshness(label);
  const qualityScore = evidence
    ? Math.max(0, Math.min(100, 65 + evidence.contentOk * 8 - evidence.contentBad * 8 + evidence.coverOk * 3))
    : markerQuality(label, bucket);
  const stabilityScore = evidence
    ? Math.max(0, Math.min(100, 65 + evidence.pass * 8 + evidence.warn * 2 - evidence.fail * 10))
    : markerStability(label, bucket);

  let score = 4300;
  score += speedScore * 6;
  score += coverageScore * 7;
  score += freshnessScore * 5;
  score += qualityScore * 8;
  score += stabilityScore * 7;
  score += markerBonus(label);
  if (bucket === 'published' || bucket === 'romance' || bucket === 'adult') score += 350;
  if (label.includes('同人')) score -= 2200;

  if (evidence) {
    score += evidence.pass * 240 + evidence.warn * 80 - evidence.fail * 160;
  }

  score = clamp(Math.round(score), 2500, 9000);
  const tier = tierFor(score, bucket, evidence);
  return {
    sourceUrl: normalizeUrl(source.bookSourceUrl),
    sourceName: source.bookSourceName ?? '',
    tier,
    bucket,
    score,
    speedScore,
    coverageScore,
    freshnessScore,
    qualityScore,
    stabilityScore,
    note: evidence
      ? `probe100 pass=${evidence.pass} warn=${evidence.warn} fail=${evidence.fail} books=${evidence.books.slice(0, 6).join('|')}`
      : 'heuristic baseline',
  };
}

function isCompatible(source) {
  const label = `${source.bookSourceName ?? ''}\n${source.bookSourceUrl ?? ''}`.toLowerCase();
  if (label.includes('同人')) return false;
  if (!hasText(source.searchUrl)) return false;
  if (!hasRules(source.ruleSearch, ['bookList', 'name', 'bookUrl'])) return false;
  if (!hasRules(source.ruleToc, ['chapterList', 'chapterName', 'chapterUrl'])) return false;
  if (!hasRules(source.ruleContent, ['content'])) return false;
  const criticalRules = [
    source.searchUrl,
    rule(source.ruleSearch, 'bookList'),
    rule(source.ruleSearch, 'name'),
    rule(source.ruleSearch, 'bookUrl'),
    rule(source.ruleBookInfo, 'tocUrl'),
    rule(source.ruleToc, 'chapterList'),
    rule(source.ruleToc, 'chapterName'),
    rule(source.ruleToc, 'chapterUrl'),
    rule(source.ruleContent, 'content'),
  ];
  return !criticalRules.some(hasUnsupportedCriticalRule);
}

function hasRules(ruleSet, names) {
  return names.every(name => hasText(rule(ruleSet, name)));
}

function rule(ruleSet, name) {
  return ruleSet?.[name] ?? '';
}

function hasUnsupportedCriticalRule(value) {
  const rule = String(value ?? '');
  const lower = rule.toLowerCase();
  if (lower.includes('<js>')) return true;
  const beforeJs = substringBeforeIgnoreCase(rule, '@js:').trim();
  if (lower.includes('@js:') && beforeJs === '') return true;
  const check = beforeJs.toLowerCase();
  return ['java.ajax', 'java.put', 'eval(', 'source.', 'cookie.', '{{eval', '{{java.put']
    .some(marker => check.includes(marker));
}

function substringBeforeIgnoreCase(value, delimiter) {
  const lower = value.toLowerCase();
  const marker = delimiter.toLowerCase();
  const index = lower.indexOf(marker);
  return index < 0 ? value : value.slice(0, index);
}

function bucketFor(label) {
  if (includesAny(label, ['出版', '文学', '实体', '完本神站'])) return 'published';
  if (includesAny(label, ['女频', '言情', '晋江', '红袖', '潇湘'])) return 'romance';
  if (includesAny(label, ['po18', '海棠', '成人', '草榴', '2048', '色花', '辣文', '肉文'])) return 'adult';
  if (includesAny(label, ['笔趣', '顶点', '55读书', '69书', '八一', '书海', '零点', '起点'])) return 'general';
  return 'misc';
}

function markerBonus(label) {
  const markers = [
    ['55读书', 2200],
    ['笔趣阁22', 1100],
    ['新笔趣', 900],
    ['顶点', 650],
    ['69书', 620],
    ['八一', 560],
    ['书海阁', 1050],
    ['书海', 900],
    ['零点', 480],
    ['笔趣', 420],
  ];
  for (const [marker, bonus] of markers) {
    if (label.includes(marker.toLowerCase())) return bonus;
  }
  return 0;
}

function markerFreshness(label) {
  return includesAny(label, ['连载', '更新', '笔趣', '新笔趣', '55读书']) ? 62 : 45;
}

function markerQuality(label, bucket) {
  if (bucket === 'published' || bucket === 'romance') return 58;
  if (bucket === 'adult') return 50;
  return includesAny(label, ['笔趣阁22', '55读书']) ? 70 : 55;
}

function markerStability(label, bucket) {
  if (bucket === 'adult') return 45;
  return includesAny(label, ['笔趣阁22', '55读书', '顶点']) ? 68 : 52;
}

function speedScoreFor(respondTime) {
  const ms = Number.parseInt(String(respondTime ?? ''), 10);
  if (!Number.isFinite(ms) || ms <= 0) return 45;
  if (ms <= 500) return 95;
  if (ms <= 1000) return 85;
  if (ms <= 2000) return 72;
  if (ms <= 3500) return 58;
  if (ms <= 6000) return 42;
  return 25;
}

function tierFor(score, bucket, evidence) {
  if (evidence && evidence.pass + evidence.warn >= 3 && evidence.fail <= evidence.warn + 1 && score >= 7800) return 1;
  if (!evidence && score >= 8000) return 1;
  if (score >= 6400) return 2;
  if ((bucket === 'published' || bucket === 'romance' || bucket === 'adult') && score >= 5200) return 2;
  return 3;
}

function normalizeUrl(value) {
  return String(value ?? '').trim().replace(/\/+$/, '');
}

function hasText(value) {
  return String(value ?? '').trim().length > 0;
}

function includesAny(value, markers) {
  return markers.some(marker => value.includes(marker.toLowerCase()));
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function clean(value) {
  return String(value ?? '').replaceAll('\t', ' ').replaceAll('\n', ' ').replaceAll('\r', ' ').trim();
}
