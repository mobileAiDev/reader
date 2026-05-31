import fs from 'node:fs';
import path from 'node:path';

const repoRoot = process.cwd();
const args = parseArgs(process.argv.slice(2));
const candidatePath = path.resolve(repoRoot, args.candidate ?? 'tools/source-quality/candidates/source-candidates-deduped.json');
const samplePath = path.resolve(repoRoot, args.sample ?? 'tools/source-quality/source-quality-sample-books.json');
const probeDir = path.resolve(repoRoot, args.probeDir ?? 'build/source-quality/probe-runs');
const outputPath = path.resolve(repoRoot, args.output ?? 'build/source-quality/final/book-sources-selected.json');
const seedPath = path.resolve(repoRoot, args.seed ?? 'build/source-quality/final/source-quality-seed-v1.tsv');
const reportPath = path.resolve(repoRoot, args.report ?? 'build/source-quality/final/source-selection-report.tsv');
const summaryPath = path.resolve(repoRoot, args.summary ?? 'build/source-quality/final/source-selection-summary.md');
const appSourcePath = path.resolve(repoRoot, args.appSource ?? 'app/src/main/assets/source-engine/book-sources.json');
const appSeedPath = path.resolve(repoRoot, args.appSeed ?? 'app/src/main/assets/source-quality-seed-v1.tsv');
const runtimePreservePath = path.resolve(
  repoRoot,
  args.runtimePreserve ?? 'build/source-quality/runtime-preserved-sources.tsv'
);
const preserveSeedPath = args.preserveSeed ? path.resolve(repoRoot, args.preserveSeed) : null;
const cap = Math.min(
  Number.parseInt(args.cap ?? '1000', 10),
  Number.parseInt(args.hardCap ?? '1000', 10)
);
const applyToApp = args.apply === 'true';

const candidates = readJsonArray(candidatePath);
const samples = readSampleMap(samplePath);
const candidateByBaseUrl = new Map();
for (const source of candidates) {
  const baseUrl = normalizeBaseUrl(source.bookSourceUrl);
  if (hasDeletedSourceLabel(source)) continue;
  if (baseUrl && !candidateByBaseUrl.has(baseUrl)) {
    candidateByBaseUrl.set(baseUrl, source);
  }
}

const probeFiles = collectProbeFiles(args.probe, probeDir);
if (probeFiles.length === 0) {
  throw new Error(`no source-quality lab TSV files found under ${path.relative(repoRoot, probeDir)}`);
}

const aggregates = new Map();
const statusByBaseUrl = new Map();
for (const file of probeFiles) {
  const rows = parseTsv(fs.readFileSync(file, 'utf8'));
  for (const row of rows) {
    const status = row.status ?? '';
    const baseUrl = normalizeBaseUrl(row.sourceUrl);
    if (!baseUrl) continue;
    rememberStatus(statusByBaseUrl, baseUrl, status);
    if (status !== 'AVAILABLE') continue;
    const source = candidateByBaseUrl.get(baseUrl);
    if (!source) continue;
    const aggregate = aggregates.get(baseUrl) ?? createAggregate(baseUrl, source, samples);
    addProbeRow(aggregate, row, samples);
    aggregates.set(baseUrl, aggregate);
  }
}

const ranked = [...aggregates.values()]
  .map(finalizeAggregate)
  .filter(item => item.readableSamples.size > 0)
  .sort(compareRanked);

if (ranked.length === 0) {
  throw new Error('probe evidence contains no AVAILABLE rows that match the candidate JSON');
}

const runtimePreserved = readRuntimePreservedSources(runtimePreservePath, candidateByBaseUrl, statusByBaseUrl);
const seedPreserved = preserveSeedPath ? readPreservedSeedSources(preserveSeedPath, candidateByBaseUrl, statusByBaseUrl) : [];
const preserved = mergePreservedSources([...runtimePreserved, ...seedPreserved], ranked);
const selected = selectSources(ranked, cap, samples, preserved);
const generatedAt = new Date().toISOString();
const selectedSources = selected.map(item => item.source);
const statusCounts = countStatuses(statusByBaseUrl);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(selectedSources, null, 2)}\n`, 'utf8');
fs.writeFileSync(seedPath, buildSeedTsv(selected, generatedAt, probeFiles), 'utf8');
fs.writeFileSync(reportPath, buildReportTsv(selected), 'utf8');
fs.writeFileSync(summaryPath, buildSummary(selected, ranked, probeFiles, statusCounts, generatedAt), 'utf8');

if (applyToApp) {
  fs.copyFileSync(outputPath, appSourcePath);
  fs.copyFileSync(seedPath, appSeedPath);
}

console.error(`probeFiles=${probeFiles.length}`);
console.error(`availableCandidates=${ranked.length}`);
console.error(`selected=${selected.length}`);
console.error(`preserved=${selected.filter(item => item.preserved).length}`);
console.error(`preserveOnly=${selected.filter(item => item.preserved && item.readableSamples.size === 0).length}`);
console.error(`compatibilityDemoted=${selected.filter(item => item.compatibilityDemoted).length}`);
console.error(`runtimePreserved=${runtimePreserved.length}`);
console.error(`tier1=${selected.filter(item => item.finalTier === 1).length}`);
console.error(`tier2=${selected.filter(item => item.finalTier === 2).length}`);
console.error(`tier3=${selected.filter(item => item.finalTier === 3).length}`);
console.error(`output=${path.relative(repoRoot, outputPath).replaceAll('\\', '/')}`);
console.error(`seed=${path.relative(repoRoot, seedPath).replaceAll('\\', '/')}`);
console.error(`report=${path.relative(repoRoot, reportPath).replaceAll('\\', '/')}`);
if (applyToApp) {
  console.error(`appliedSource=${path.relative(repoRoot, appSourcePath).replaceAll('\\', '/')}`);
  console.error(`appliedSeed=${path.relative(repoRoot, appSeedPath).replaceAll('\\', '/')}`);
}

function createAggregate(baseUrl, source, sampleMap) {
  return {
    baseUrl,
    source,
    sourceName: String(source.bookSourceName ?? ''),
    sourceUrl: String(source.bookSourceUrl ?? ''),
    host: siteFamily(source.bookSourceUrl),
    bestScore: 0,
    bestContentQuality: 0,
    bestContentCoherence: 0,
    bestContentLength: 0,
    availableSampleCount: 0,
    failedSampleCount: 0,
    sampleCount: 0,
    searchEmptyCount: 0,
    failedHitCount: 0,
    searchMismatchCount: 0,
    durations: [],
    searchDurations: [],
    readableSamples: new Set(),
    rareReadableKeywords: new Set(),
    bucketHits: new Map(),
    sampleMap,
  };
}

function addProbeRow(aggregate, row, sampleMap) {
  aggregate.bestScore = Math.max(aggregate.bestScore, toNumber(row.score));
  aggregate.bestContentQuality = Math.max(aggregate.bestContentQuality, toNumber(row.contentQuality));
  aggregate.bestContentCoherence = Math.max(aggregate.bestContentCoherence, toNumber(row.contentCoherence));
  aggregate.bestContentLength = Math.max(aggregate.bestContentLength, toNumber(row.contentLength));
  aggregate.availableSampleCount += toNumber(row.availableSampleCount);
  aggregate.failedSampleCount += toNumber(row.failedSampleCount);
  aggregate.sampleCount += toNumber(row.sampleCount);
  aggregate.searchEmptyCount += metricFromMessage(row.message, /searchEmpty=(\d+)/);
  aggregate.failedHitCount += metricFromMessage(row.message, /failedHits=(\d+)\//);
  aggregate.searchMismatchCount += statusCountFromMessage(row.message, 'SEARCH_MISMATCH');
  addDuration(aggregate.durations, row.durationMs);
  addDuration(aggregate.searchDurations, row.searchMs);
  splitPipe(row.readableSamples).forEach(title => {
    aggregate.readableSamples.add(title);
    const bucket = sampleMap.get(title)?.bucket ?? 'unknown';
    aggregate.bucketHits.set(bucket, (aggregate.bucketHits.get(bucket) ?? 0) + 1);
  });
  splitPipe(row.rareReadableKeywords).forEach(title => aggregate.rareReadableKeywords.add(title));
}

function finalizeAggregate(item) {
  const mainstreamHits = [...item.readableSamples]
    .filter(title => ['classic', 'qidian', 'fanqie'].includes(item.sampleMap.get(title)?.bucket))
    .length;
  const breadthHits = [...item.readableSamples]
    .filter(title => ['rare', 'published', 'category', 'breadth'].includes(item.sampleMap.get(title)?.bucket))
    .length;
  const medianDuration = median(item.durations);
  const medianSearch = median(item.searchDurations);
  const durationPerSample = Math.floor(medianDuration / Math.max(1, item.sampleCount));
  const searchPerSample = Math.floor(medianSearch / Math.max(1, item.sampleCount));
  const meaningfulFailures = item.failedHitCount + item.searchMismatchCount;
  const speedScore = speedScoreFor(durationPerSample);
  const coverageScore = Math.min(100, item.readableSamples.size * 8 + mainstreamHits * 5 + breadthHits * 6);
  const qualityScore = Math.max(item.bestContentQuality, Math.floor(item.bestScore / 100));
  const stabilityScore = Math.max(0, Math.min(100, 100 - meaningfulFailures * 5));
  const rareBoost = item.rareReadableKeywords.size * 180;
  const coverageBoost = item.readableSamples.size * 70 + mainstreamHits * 60 + breadthHits * 80;
  const failurePenalty = meaningfulFailures * 90;
  const latencyPenalty = Math.min(800, Math.floor(durationPerSample / 50));
  const finalScore = Math.max(0, Math.min(10000,
    item.bestScore + speedScore * 8 + coverageBoost + rareBoost - failurePenalty - latencyPenalty
  ));
  return {
    ...item,
    mainstreamHits,
    breadthHits,
    medianDuration,
    medianSearch,
    durationPerSample,
    searchPerSample,
    meaningfulFailures,
    speedScore,
    coverageScore,
    qualityScore,
    stabilityScore,
    finalScore,
    baseTier: baseTierFor(item, finalScore, mainstreamHits, breadthHits, durationPerSample, meaningfulFailures),
  };
}

function baseTierFor(item, finalScore, mainstreamHits, breadthHits, durationPerSample, meaningfulFailures) {
  const toleratedFailures = Math.max(4, Math.floor(item.availableSampleCount * 0.45));
  if (
    finalScore >= 8200 &&
    item.bestContentQuality >= 80 &&
    item.bestContentCoherence >= 80 &&
    mainstreamHits >= 3 &&
    durationPerSample <= 8000 &&
    meaningfulFailures <= toleratedFailures &&
    !hasTier1RestrictedLabel(item)
  ) {
    return 1;
  }
  if (finalScore >= 6800 || item.rareReadableKeywords.size > 0 || breadthHits > 0) {
    return 2;
  }
  return 3;
}

function selectSources(ranked, maxCount, sampleMap, preservedItems) {
  const selected = [];
  const selectedKeys = new Set();
  const hostCounts = new Map();
  const titleCoverage = new Map();
  const bucketCoverage = new Map();

  const tier1Limit = Math.min(160, Math.max(40, Math.floor(maxCount * 0.35)));
  const tier2Limit = Math.min(260, Math.max(80, Math.floor(maxCount * 0.55)));

  const mainstreamTitles = titlesInBuckets(sampleMap, ['classic', 'qidian', 'fanqie']);
  const breadthTitles = titlesInBuckets(sampleMap, ['rare', 'published', 'category', 'breadth']);

  for (const item of preservedItems) {
    addPreserved(item);
  }

  coverBuckets(['qidian', 'fanqie', 'classic'], 1, 3, tier1Limit, 6, 10);
  addMany(
    ranked.filter(item => item.baseTier === 1 && item.mainstreamHits >= 2),
    1,
    tier1Limit,
    6,
    10,
    { requireNewMainstreamCoverage: true }
  );

  coverTitles(breadthTitles, 2, 2, 4, 5);
  coverBuckets(['rare', 'published', 'category', 'breadth'], 2, 2, tier2Limit, 4, 6);
  addMany(
    ranked.filter(item => item.baseTier === 2 && item.rareReadableKeywords.size > 0),
    2,
    tier2Limit,
    4,
    6,
    { requireNewBreadthCoverage: true }
  );
  coverTitles(mainstreamTitles, 2, 2, 4, 6);
  addMany(
    ranked.filter(item => item.baseTier === 2),
    2,
    tier2Limit,
    4,
    6,
    { preferNewCoverage: true }
  );

  coverTitles([...sampleMap.keys()], 3, 1, 5, 8);
  addMany(
    ranked.filter(item => item.baseTier === 3),
    3,
    maxCount,
    5,
    8,
    { preferNewCoverage: true, preferNewHost: true }
  );

  if (selected.length < maxCount) {
    addMany(ranked, 3, maxCount, 5, 8, { fill: true });
  }

  return selected
    .sort((a, b) =>
      a.finalTier - b.finalTier ||
      bucketPriority(bucketFor(a)) - bucketPriority(bucketFor(b)) ||
      b.finalScore - a.finalScore ||
      b.speedScore - a.speedScore ||
      b.rareReadableKeywords.size - a.rareReadableKeywords.size ||
      a.sourceName.localeCompare(b.sourceName, 'zh-Hans-CN')
    )
    .slice(0, maxCount);

  function coverBuckets(buckets, tier, targetPerBucket, tierLimit, hostTierCap, hostTotalCap) {
    for (const bucket of buckets) {
      while ((bucketCoverage.get(bucket) ?? 0) < targetPerBucket && selected.length < maxCount) {
        const item = ranked.find(candidate =>
          !selectedKeys.has(candidate.baseUrl) &&
          bucketHitCount(candidate, bucket) > 0 &&
          (tier === 1 ? candidate.baseTier === 1 : candidate.baseTier <= tier)
        );
        if (!item || selected.filter(row => row.finalTier === tier).length >= tierLimit) break;
        if (!addOne(item, tier, hostTotalCap, hostTierCap)) break;
      }
    }
  }

  function coverTitles(titles, tier, targetPerTitle, hostTierCap, hostTotalCap) {
    for (const title of titles) {
      while ((titleCoverage.get(title) ?? 0) < targetPerTitle && selected.length < maxCount) {
        const item = ranked.find(candidate =>
          !selectedKeys.has(candidate.baseUrl) &&
          candidate.readableSamples.has(title) &&
          candidate.baseTier <= tier
        );
        if (!item) break;
        if (!addOne(item, tier, hostTotalCap, hostTierCap)) break;
      }
    }
  }

  function addMany(items, tier, limit, hostTierCap, hostTotalCap, options = {}) {
    const ordered = [...items].sort((a, b) => optionRank(b, options) - optionRank(a, options) || compareRanked(a, b));
    for (const item of ordered) {
      if (selected.length >= maxCount) break;
      if (selected.filter(row => row.finalTier === tier).length >= limit && tier < 3) break;
      addOne(item, tier, hostTotalCap, hostTierCap);
    }
  }

  function addOne(item, tier, hostTotalCap, hostTierCap = hostTotalCap) {
    if (selected.length >= maxCount || selectedKeys.has(item.baseUrl)) return false;
    const host = item.host || item.baseUrl;
    const totalForHost = hostCounts.get(host) ?? 0;
    const tierForHost = selected.filter(row => (row.host || row.baseUrl) === host && row.finalTier === tier).length;
    if (totalForHost >= hostTotalCap || tierForHost >= hostTierCap) return false;
    item.finalTier = tier;
    selected.push(item);
    selectedKeys.add(item.baseUrl);
    hostCounts.set(host, totalForHost + 1);
    item.readableSamples.forEach(title => {
      titleCoverage.set(title, (titleCoverage.get(title) ?? 0) + 1);
      const bucket = sampleMap.get(title)?.bucket ?? 'unknown';
      bucketCoverage.set(bucket, (bucketCoverage.get(bucket) ?? 0) + 1);
    });
    return true;
  }

  function addPreserved(item) {
    if (selected.length >= maxCount || selectedKeys.has(item.baseUrl)) return false;
    item.finalTier = item.preservedTier;
    item.preserved = true;
    selected.push(item);
    selectedKeys.add(item.baseUrl);
    const host = item.host || item.baseUrl;
    hostCounts.set(host, (hostCounts.get(host) ?? 0) + 1);
    item.readableSamples.forEach(title => {
      titleCoverage.set(title, (titleCoverage.get(title) ?? 0) + 1);
      const bucket = sampleMap.get(title)?.bucket ?? 'unknown';
      bucketCoverage.set(bucket, (bucketCoverage.get(bucket) ?? 0) + 1);
    });
    return true;
  }

  function optionRank(item, options) {
    let rank = 0;
    if (options.requireNewMainstreamCoverage || options.preferNewCoverage) {
      rank += [...item.readableSamples].filter(title => (titleCoverage.get(title) ?? 0) === 0).length * 50;
    }
    if (options.requireNewBreadthCoverage) {
      rank += [...item.readableSamples]
        .filter(title => breadthTitles.includes(title) && (titleCoverage.get(title) ?? 0) === 0)
        .length * 120;
      rank += item.rareReadableKeywords.size * 80;
    }
    if (options.preferNewHost && !hostCounts.has(item.host || item.baseUrl)) rank += 30;
    if (options.fill) rank += item.finalScore / 1000;
    return rank;
  }
}

function readRuntimePreservedSources(file, candidateByBaseUrl, statusByBaseUrl) {
  if (!fs.existsSync(file)) return [];
  const rows = parseTsv(fs.readFileSync(file, 'utf8'));
  const preserved = [];
  const seen = new Set();
  for (const row of rows) {
    const baseUrl = normalizeBaseUrl(row.sourceUrl);
    const source = candidateByBaseUrl.get(baseUrl);
    if (!source || seen.has(baseUrl)) continue;
    const compatibilityStatus = statusByBaseUrl.get(baseUrl) ?? '';
    const demoteForCompatibility = compatibilityStatus === 'INCOMPATIBLE';
    const tier = demoteForCompatibility ? 3 : clampTier(toNumber(row.tier));
    const score = demoteForCompatibility ? 900 : Math.max(toNumber(row.score), preservedTierScore(tier));
    const item = createAggregate(baseUrl, source, new Map());
    item.bestScore = score;
    item.sourceName = String(source.bookSourceName ?? row.sourceName ?? '');
    item.sourceUrl = String(source.bookSourceUrl ?? row.sourceUrl ?? '');
    item.speedScore = 0;
    item.coverageScore = 0;
    item.qualityScore = 0;
    item.stabilityScore = 0;
    item.finalScore = score;
    item.baseTier = tier;
    item.finalTier = tier;
    item.preservedTier = tier;
    item.preserved = true;
    item.preserveReason = row.reasons ?? 'runtime';
    item.runtimeBooks = row.books ?? '';
    item.compatibilityStatus = compatibilityStatus;
    item.compatibilityDemoted = demoteForCompatibility;
    preserved.push(item);
    seen.add(baseUrl);
  }
  return preserved.sort((a, b) => a.preservedTier - b.preservedTier || b.finalScore - a.finalScore);
}

function readPreservedSeedSources(file, candidateByBaseUrl, statusByBaseUrl) {
  if (!fs.existsSync(file)) return [];
  const rows = parseTsv(fs.readFileSync(file, 'utf8'));
  const preserved = [];
  const seen = new Set();
  for (const row of rows) {
    if ((row.kind ?? '').trim() !== 'source') continue;
    const tier = toNumber(row.tier);
    if (tier < 1 || tier > 3) continue;
    const baseUrl = normalizeBaseUrl(row.sourceUrl);
    const source = candidateByBaseUrl.get(baseUrl);
    if (!source || seen.has(baseUrl)) continue;
    const item = createAggregate(baseUrl, source, new Map());
    item.bestScore = Math.max(toNumber(row.score), 6500);
    item.sourceName = String(source.bookSourceName ?? row.sourceName ?? '');
    item.sourceUrl = String(source.bookSourceUrl ?? row.sourceUrl ?? '');
    item.speedScore = toNumber(row.speed);
    item.coverageScore = toNumber(row.coverage);
    item.qualityScore = toNumber(row.quality);
    item.stabilityScore = toNumber(row.stability);
    item.finalScore = Math.max(toNumber(row.score), 6500);
    item.baseTier = tier;
    item.finalTier = tier;
    item.preservedTier = tier;
    item.preserved = true;
    item.preserveReason = 'seed';
    item.runtimeBooks = '';
    item.compatibilityStatus = statusByBaseUrl.get(baseUrl) ?? '';
    item.compatibilityDemoted = false;
    preserved.push(item);
    seen.add(baseUrl);
  }
  return preserved.sort((a, b) => a.preservedTier - b.preservedTier || b.finalScore - a.finalScore);
}

function mergePreservedSources(items, rankedItems) {
  const rankedByBaseUrl = new Map(rankedItems.map(item => [item.baseUrl, item]));
  const output = [];
  const seen = new Set();
  for (const item of items) {
    if (seen.has(item.baseUrl)) continue;
    const ranked = rankedByBaseUrl.get(item.baseUrl);
    const merged = ranked ?? item;
    merged.finalScore = Math.max(merged.finalScore ?? 0, item.finalScore ?? 0);
    merged.bestScore = Math.max(merged.bestScore ?? 0, item.bestScore ?? 0);
    merged.preservedTier = Math.min(clampTier(item.preservedTier), clampTier(merged.baseTier ?? item.preservedTier));
    merged.preserved = true;
    merged.preserveReason = item.preserveReason ?? merged.preserveReason ?? 'runtime';
    merged.runtimeBooks = item.runtimeBooks ?? merged.runtimeBooks ?? '';
    merged.compatibilityStatus = item.compatibilityStatus ?? merged.compatibilityStatus ?? '';
    merged.compatibilityDemoted = item.compatibilityDemoted || merged.compatibilityDemoted || false;
    output.push(merged);
    seen.add(item.baseUrl);
  }
  return output.sort((a, b) => a.preservedTier - b.preservedTier || b.finalScore - a.finalScore);
}

function rememberStatus(statusByBaseUrl, baseUrl, status) {
  const current = statusByBaseUrl.get(baseUrl);
  if (!current || statusPriority(status) < statusPriority(current)) {
    statusByBaseUrl.set(baseUrl, status);
  }
}

function countStatuses(statusByBaseUrl) {
  const counts = new Map();
  for (const status of statusByBaseUrl.values()) {
    counts.set(status, (counts.get(status) ?? 0) + 1);
  }
  return counts;
}

function statusPriority(status) {
  return {
    AVAILABLE: 0,
    CONTENT_LOW_QUALITY: 1,
    CONTENT_FAILED: 2,
    CATALOG_EMPTY: 3,
    CATALOG_FAILED: 4,
    DETAIL_FAILED: 5,
    SEARCH_MISMATCH: 6,
    SEARCH_EMPTY: 7,
    DISABLED: 8,
    INCOMPATIBLE: 9,
    SKIPPED_BY_LIMIT: 10,
  }[status] ?? 11;
}

function titlesInBuckets(sampleMap, buckets) {
  return [...sampleMap.entries()]
    .filter(([, sample]) => buckets.includes(sample.bucket))
    .map(([title]) => title);
}

function bucketHitCount(item, bucket) {
  return [...item.readableSamples].filter(title => item.sampleMap.get(title)?.bucket === bucket).length;
}

function bucketPriority(bucket) {
  return {
    qidian: 0,
    fanqie: 1,
    classic: 2,
    rare: 3,
    breadth: 4,
    published: 5,
    category: 6,
    general: 7,
    unknown: 8,
  }[bucket] ?? 9;
}

function hasTier1RestrictedLabel(item) {
  const text = `${item.sourceName} ${item.sourceUrl}`.toLowerCase();
  return [
    '已失效',
    '失效',
    '禁用',
    'cookie',
    '🔞',
    '成人',
    'po18',
    'popo',
    'po文',
    '肉',
    'rousewu',
    '海棠',
    'haitang',
    '御宅',
    'yuzhai',
    '御书',
    'yushuwu',
  ].some(marker => text.includes(marker));
}

function hasDeletedSourceLabel(source) {
  const text = `${source.bookSourceName ?? ''} ${source.bookSourceUrl ?? ''}`.toLowerCase();
  return ['已失效', '失效', '禁用'].some(marker => text.includes(marker));
}

function compareRanked(a, b) {
  return a.baseTier - b.baseTier ||
    b.finalScore - a.finalScore ||
    b.mainstreamHits - a.mainstreamHits ||
    b.breadthHits - a.breadthHits ||
    a.medianDuration - b.medianDuration ||
    a.sourceName.localeCompare(b.sourceName, 'zh-Hans-CN');
}

function buildSeedTsv(selected, generatedAt, probeFiles) {
  const lines = [
    '# source-quality-seed-v1',
    `# generatedAt=${generatedAt}`,
    `# probeEvidence=${probeFiles.map(file => path.relative(repoRoot, file).replaceAll('\\', '/')).join('|')}`,
    '# generatedBy=tools/source-quality/select-final-sources.mjs',
    'kind\tsourceUrl\tsourceName\ttier\tbucket\tscore\tspeed\tcoverage\tfreshness\tquality\tstability\tnote',
  ];
  for (const item of selected) {
    lines.push([
      'source',
      tsv(item.sourceUrl),
      tsv(item.sourceName),
      item.finalTier,
      bucketFor(item),
      item.finalScore,
      item.speedScore,
      item.coverageScore,
      0,
      item.qualityScore,
      item.stabilityScore,
      tsv(
        `readable=${[...item.readableSamples].join('|')} ` +
        `rare=${[...item.rareReadableKeywords].join('|')} ` +
        `preserve=${item.preserveReason ?? ''} books=${item.runtimeBooks ?? ''} ` +
        `compatibility=${item.compatibilityStatus ?? ''} demoted=${item.compatibilityDemoted ? 'true' : 'false'}`
      ),
    ].join('\t'));
  }
  return `${lines.join('\n')}\n`;
}

function buildReportTsv(selected) {
  const header = [
    'tier',
    'finalScore',
    'sourceName',
    'sourceUrl',
    'baseUrl',
    'host',
    'readableSamples',
    'rareReadableKeywords',
    'availableSampleCount',
    'failedSampleCount',
    'searchEmptyCount',
    'failedHitCount',
    'searchMismatchCount',
    'meaningfulFailures',
    'medianDurationMs',
    'medianSearchMs',
    'durationPerSampleMs',
    'searchPerSampleMs',
    'speedScore',
    'coverageScore',
    'qualityScore',
    'stabilityScore',
    'preserved',
    'preserveReason',
    'runtimeBooks',
    'compatibilityStatus',
    'compatibilityDemoted',
  ];
  const rows = selected.map(item => [
    item.finalTier,
    item.finalScore,
    tsv(item.sourceName),
    tsv(item.sourceUrl),
    item.baseUrl,
    item.host,
    tsv([...item.readableSamples].join('|')),
    tsv([...item.rareReadableKeywords].join('|')),
    item.availableSampleCount,
    item.failedSampleCount,
    item.searchEmptyCount,
    item.failedHitCount,
    item.searchMismatchCount,
    item.meaningfulFailures,
    item.medianDuration,
    item.medianSearch,
    item.durationPerSample,
    item.searchPerSample,
    item.speedScore,
    item.coverageScore,
    item.qualityScore,
    item.stabilityScore,
    item.preserved ? 'true' : 'false',
    tsv(item.preserveReason ?? ''),
    tsv(item.runtimeBooks ?? ''),
    tsv(item.compatibilityStatus ?? ''),
    item.compatibilityDemoted ? 'true' : 'false',
  ].join('\t'));
  return `${[header.join('\t'), ...rows].join('\n')}\n`;
}

function buildSummary(selected, ranked, probeFiles, statusCounts, generatedAt) {
  const statusText = [...statusCounts.entries()]
    .sort((a, b) => a[0].localeCompare(b[0], 'en'))
    .map(([status, count]) => `- ${status}: ${count}`)
    .join('\n');
  const topRare = selected
    .filter(item => item.rareReadableKeywords.size > 0)
    .slice(0, 30)
    .map(item => `- tier${item.finalTier} ${item.sourceName}: ${[...item.rareReadableKeywords].join('|')}`)
    .join('\n');
  return `# Source Selection Summary

generatedAt=${generatedAt}

- probeFiles: ${probeFiles.length}
- availableCandidates: ${ranked.length}
- selected: ${selected.length}
- preserved: ${selected.filter(item => item.preserved).length}
- preserveOnly: ${selected.filter(item => item.preserved && item.readableSamples.size === 0).length}
- compatibilityDemoted: ${selected.filter(item => item.compatibilityDemoted).length}
- tier1: ${selected.filter(item => item.finalTier === 1).length}
- tier2: ${selected.filter(item => item.finalTier === 2).length}
- tier3: ${selected.filter(item => item.finalTier === 3).length}

## Probe Status Counts

${statusText}

## Rare Breadth Kept

${topRare || '- none'}
`;
}

function readJsonArray(file) {
  const json = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  if (!Array.isArray(json)) throw new Error(`expected JSON array: ${file}`);
  return json;
}

function readSampleMap(file) {
  const json = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  const map = new Map();
  for (const item of json.selectionSample ?? json.smokeSample ?? []) {
    if (!item.title) continue;
    map.set(String(item.title), {
      bucket: String(item.bucket ?? 'unknown'),
      weight: Number(item.weight ?? 1),
    });
  }
  return map;
}

function collectProbeFiles(probeArg, dir) {
  if (probeArg) {
    return probeArg.split(/[;,]/)
      .map(item => path.resolve(repoRoot, item.trim()))
      .filter(Boolean);
  }
  if (!fs.existsSync(dir)) return [];
  return walk(dir)
    .filter(file => file.endsWith('.tsv'))
    .filter(file => {
      const firstLine = fs.readFileSync(file, 'utf8').split(/\r?\n/, 1)[0] ?? '';
      return firstLine.includes('sourceUrl') && firstLine.includes('status');
    })
    .sort();
}

function walk(dir) {
  const output = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) output.push(...walk(file));
    if (entry.isFile()) output.push(file);
  }
  return output;
}

function parseTsv(text) {
  const lines = text.split(/\r?\n/).filter(Boolean);
  if (lines.length < 2) return [];
  const header = lines[0].split('\t');
  return lines.slice(1).map(line => {
    const cells = line.split('\t');
    const row = {};
    header.forEach((name, index) => {
      row[name] = cells[index] ?? '';
    });
    return row;
  });
}

function normalizeBaseUrl(value) {
  let text = String(value ?? '').trim();
  if (!text) return '';
  text = text.split('##')[0].split('#')[0].trim();
  text = text.replace(/[?].*$/, '').replace(/\/+$/, '');
  try {
    const url = new URL(text);
    return `${url.hostname.toLowerCase()}${url.pathname.replace(/\/+$/, '')}`;
  } catch {
    return text.toLowerCase();
  }
}

function siteFamily(value) {
  let text = String(value ?? '').trim().split('##')[0].split('#')[0].trim();
  try {
    const url = new URL(text);
    return url.hostname.toLowerCase().replace(/^(www|m|wap|wap\d+)\./, '');
  } catch {
    return normalizeBaseUrl(text);
  }
}

function splitPipe(value) {
  return String(value ?? '')
    .split('|')
    .map(item => item.trim())
    .filter(Boolean);
}

function metricFromMessage(value, pattern) {
  const match = String(value ?? '').match(pattern);
  return match ? toNumber(match[1]) : 0;
}

function statusCountFromMessage(value, status) {
  const escaped = status.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return metricFromMessage(value, new RegExp(`${escaped}=(\\d+)`));
}

function toNumber(value) {
  const number = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(number) ? number : 0;
}

function clampTier(value) {
  return value >= 1 && value <= 3 ? value : 2;
}

function preservedTierScore(tier) {
  return {
    1: 8200,
    2: 7200,
    3: 6100,
  }[clampTier(tier)];
}

function addDuration(list, value) {
  const number = toNumber(value);
  if (number > 0) list.push(number);
}

function median(values) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function speedScoreFor(ms) {
  if (!ms) return 50;
  if (ms <= 3000) return 100;
  if (ms <= 8000) return 85;
  if (ms <= 15000) return 70;
  if (ms <= 25000) return 55;
  return 35;
}

function bucketFor(item) {
  if (item.rareReadableKeywords.size > 0) return 'breadth';
  const buckets = [...item.bucketHits.entries()].sort((a, b) => b[1] - a[1]);
  return buckets[0]?.[0] ?? 'general';
}

function tsv(value) {
  return String(value ?? '').replaceAll('\t', ' ').replaceAll('\r', ' ').replaceAll('\n', ' ');
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
