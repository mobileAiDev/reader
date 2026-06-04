#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');
const novelSourcesPath = path.join(repoRoot, 'app', 'src', 'main', 'assets', 'source-engine', 'book-sources.json');
const mediaSourcesPath = path.join(repoRoot, 'app', 'src', 'main', 'assets', 'media-source-engine', 'media-sources.json');
const mediaSeedPath = path.join(repoRoot, 'app', 'src', 'main', 'assets', 'media-source-quality-seed-v1.tsv');
const sourceJsonDir = path.join(repoRoot, 'bridge-artifacts', 'source-json');
const legadoDefaultSourcesPath = process.env.LEGADO_DEFAULT_SOURCES_JSON ||
  'C:/project/legado/app/src/main/assets/defaultData/bookSources.json';
const MEDIA_SOURCE_PROVENANCE = 'media-sources.json';

const TEXT_SOURCE_TYPE = 0;
const MEDIA_KINDS = new Map([
  [1, 'audio'],
  [2, 'comic'],
]);

const HIGH_PRIORITY_MARKERS = {
  audio: ['喜马拉雅', '懒人', '有声', '听书', '恋听', '书音'],
  comic: ['包子', '拷贝', '快看', '漫客栈', '看漫画', '动漫之家', '漫画台', '爱优漫', '酷看', '漫神'],
};
const LOW_PRIORITY_MARKERS = ['英文', '日文', 'raw', '搬运', '发现', '需vpn', 'ssr', '魔法'];

main();

function main() {
  const novelSources = readJson(novelSourcesPath)
    .filter((source) => (source.bookSourceType ?? TEXT_SOURCE_TYPE) === TEXT_SOURCE_TYPE);
  if (novelSources.length === 0) {
    throw new Error(`No novel sources found in ${novelSourcesPath}`);
  }

  const mediaSources = selectMediaSources(loadMediaCandidates());
  const seedRows = mediaSources.map(toSeedRow);

  fs.mkdirSync(path.dirname(mediaSourcesPath), { recursive: true });
  writeJson(novelSourcesPath, novelSources);
  writeJson(mediaSourcesPath, mediaSources);
  writeSeed(mediaSeedPath, seedRows);

  console.log(`Wrote ${novelSourcesPath}`);
  console.log(`Novel source counts: ${JSON.stringify(countBySourceType(novelSources))}`);
  console.log(`Wrote ${mediaSourcesPath}`);
  console.log(`Media source counts: ${JSON.stringify(countBySourceType(mediaSources))}`);
  console.log(`Wrote ${mediaSeedPath}`);
  console.log(`Media seed rows: ${seedRows.length}`);
}

function loadMediaCandidates() {
  if (fs.existsSync(legadoDefaultSourcesPath)) {
    return readJson(legadoDefaultSourcesPath)
      .map((source) => ({ ...source, __sourceFile: path.basename(legadoDefaultSourcesPath) }));
  }
  if (!fs.existsSync(sourceJsonDir)) {
    throw new Error(`Missing Legado default sources and source JSON directory: ${sourceJsonDir}`);
  }
  return fs.readdirSync(sourceJsonDir)
    .filter((fileName) => fileName.endsWith('.json'))
    .sort((a, b) => a.localeCompare(b))
    .flatMap((fileName) => {
      const filePath = path.join(sourceJsonDir, fileName);
      const sources = readJson(filePath);
      if (!Array.isArray(sources)) {
        throw new Error(`Source JSON must be an array: ${filePath}`);
      }
      return sources.map((source) => ({ ...source, __sourceFile: fileName }));
    });
}

function selectMediaSources(candidates) {
  const selected = new Map();
  for (const candidate of candidates) {
    const sourceType = candidate.bookSourceType ?? TEXT_SOURCE_TYPE;
    if (!MEDIA_KINDS.has(sourceType)) continue;
    const source = normalizeSourceForAsset(candidate);
    const key = sourceKey(source);
    if (!key || selected.has(key)) continue;
    selected.set(key, source);
  }
  return [...selected.values()].sort(mediaSourceComparator);
}

function normalizeSourceForAsset(source) {
  const output = {};
  for (const [key, value] of Object.entries(source)) {
    if (key.startsWith('__')) continue;
    output[key] = value;
  }
  output.bookSourceType = Number(output.bookSourceType ?? TEXT_SOURCE_TYPE);
  if (output.enabled === undefined) output.enabled = true;
  return output;
}

function mediaSourceComparator(left, right) {
  return (left.bookSourceType ?? TEXT_SOURCE_TYPE) - (right.bookSourceType ?? TEXT_SOURCE_TYPE) ||
    scoreSource(right) - scoreSource(left) ||
    String(left.bookSourceName ?? '').localeCompare(String(right.bookSourceName ?? ''), 'zh-Hans-CN') ||
    String(left.bookSourceUrl ?? '').localeCompare(String(right.bookSourceUrl ?? ''));
}

function scoreSource(source) {
  const kind = MEDIA_KINDS.get(source.bookSourceType ?? TEXT_SOURCE_TYPE);
  const label = `${source.bookSourceName ?? ''}\n${source.bookSourceUrl ?? ''}\n${source.bookSourceGroup ?? ''}`.toLowerCase();
  let score = 5000;
  if (label.includes('优++')) score += 2600;
  else if (label.includes('优+')) score += 2200;
  else if (label.includes('优')) score += 1300;
  score += respondTimeScore(source.respondTime);
  score += customOrderScore(source.customOrder);
  for (const marker of HIGH_PRIORITY_MARKERS[kind] ?? []) {
    if (label.includes(marker.toLowerCase())) {
      score += 450;
      break;
    }
  }
  for (const marker of LOW_PRIORITY_MARKERS) {
    if (label.includes(marker.toLowerCase())) {
      score -= 450;
      break;
    }
  }
  return clamp(score, 0, 10000);
}

function respondTimeScore(value) {
  const respondTime = Number(value);
  if (!Number.isFinite(respondTime) || respondTime <= 0) return 0;
  if (respondTime <= 1000) return 2100;
  if (respondTime <= 2500) return 1700;
  if (respondTime <= 5000) return 1300;
  if (respondTime <= 8000) return 900;
  if (respondTime <= 12000) return 550;
  if (respondTime <= 20000) return 250;
  if (respondTime >= 180000) return -1700;
  if (respondTime >= 90000) return -1000;
  if (respondTime >= 45000) return -550;
  return 0;
}

function customOrderScore(value) {
  const customOrder = Number(value);
  if (!Number.isFinite(customOrder)) return 0;
  if (customOrder > 0 && customOrder <= 250) return 250;
  if (customOrder > 0 && customOrder <= 1000) return 100;
  if (customOrder < -100000) return -350;
  return 0;
}

function toSeedRow(source) {
  const score = scoreSource(source);
  return {
    kind: MEDIA_KINDS.get(source.bookSourceType ?? TEXT_SOURCE_TYPE),
    sourceUrl: source.bookSourceUrl ?? '',
    sourceName: source.bookSourceName ?? '',
    tier: tierForScore(score),
    score,
    note: `builtin-media-source-legado:${MEDIA_SOURCE_PROVENANCE}`,
  };
}

function tierForScore(score) {
  if (score >= 7200) return 1;
  if (score >= 4800) return 2;
  return 3;
}

function sourceKey(source) {
  return `${normalize(source.bookSourceName)}|${normalize(source.bookSourceUrl)}`;
}

function normalize(value) {
  return String(value ?? '').trim().trimEnd('/').toLowerCase();
}

function readJson(filePath) {
  const value = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  if (!Array.isArray(value)) {
    throw new Error(`Source JSON must be an array: ${filePath}`);
  }
  return value;
}

function writeJson(filePath, value) {
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function writeSeed(filePath, rows) {
  const lines = [
    'kind\tsourceUrl\tsourceName\ttier\tscore\tnote',
    ...rows.map((row) => [
      row.kind,
      row.sourceUrl,
      row.sourceName,
      row.tier,
      row.score,
      row.note,
    ].map(tsvCell).join('\t')),
  ];
  fs.writeFileSync(filePath, `${lines.join('\n')}\n`, 'utf8');
}

function countBySourceType(sources) {
  return sources.reduce((counts, source) => {
    const sourceType = String(source.bookSourceType ?? TEXT_SOURCE_TYPE);
    counts[sourceType] = (counts[sourceType] ?? 0) + 1;
    return counts;
  }, {});
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function tsvCell(value) {
  return String(value ?? '').replace(/\t/g, ' ').replace(/\r?\n/g, ' ');
}
