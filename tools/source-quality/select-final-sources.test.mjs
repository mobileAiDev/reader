import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = process.cwd();
const testDir = path.join(repoRoot, 'build/source-quality/test-select-final');
fs.rmSync(testDir, { recursive: true, force: true });
fs.mkdirSync(testDir, { recursive: true });

const candidatePath = path.join(testDir, 'candidates.json');
const samplePath = path.join(testDir, 'samples.json');
const probeDir = path.join(testDir, 'probe');
const runtimePreservePath = path.join(testDir, 'runtime-preserved.tsv');
const outputPath = path.join(testDir, 'selected.json');
const seedPath = path.join(testDir, 'seed.tsv');
const reportPath = path.join(testDir, 'report.tsv');
const summaryPath = path.join(testDir, 'summary.md');

fs.mkdirSync(probeDir, { recursive: true });
fs.writeFileSync(candidatePath, JSON.stringify([
  source('Short Catalog Source', 'https://short.example'),
  source('One Short Catalog Source', 'https://one-short.example'),
  source('Good Source', 'https://good.example'),
  source('Polluted Source', 'https://polluted.example'),
  source('Low Success Ratio Source', 'https://low-ratio.example'),
  source('Single Mainstream Hit Source', 'https://single-hit.example'),
  source('Published Short Source', 'https://published-short.example'),
  source('Coverage Short Source', 'https://coverage-short.example'),
  source('Metadata Only Source', 'https://metadata.example'),
  source('No Readable Preserved Source', 'https://no-readable.example'),
], null, 2));
fs.writeFileSync(samplePath, JSON.stringify({
  selectionSample: [
    { title: 'Book A', bucket: 'classic' },
    { title: 'Book B', bucket: 'qidian' },
    { title: 'Book C', bucket: 'fanqie' },
    { title: 'Published Book', bucket: 'published' },
    { title: 'Coverage Book', bucket: 'qidian', qualityGate: false },
  ],
}, null, 2));
fs.writeFileSync(
  path.join(probeDir, 'probe.tsv'),
  [
    probeHeader().join('\t'),
    probeRow({
      index: 0,
      sourceName: 'Short Catalog Source',
      sourceUrl: 'https://short.example',
      readableSamples: 'Book A|Book B|Book C',
      shortCatalogSampleCount: 2,
      shortCatalogSamples: 'Book A|Book B',
    }).join('\t'),
    probeRow({
      index: 1,
      sourceName: 'One Short Catalog Source',
      sourceUrl: 'https://one-short.example',
      readableSamples: 'Book A|Book B|Book C',
      shortCatalogSampleCount: 1,
      shortCatalogSamples: 'Book A',
    }).join('\t'),
    probeRow({
      index: 2,
      sourceName: 'Good Source',
      sourceUrl: 'https://good.example',
      sampleCount: 8,
      availableSampleCount: 8,
      readableSamples: 'Book A|Book B|Book C',
    }).join('\t'),
    probeRow({
      index: 3,
      sourceName: 'Polluted Source',
      sourceUrl: 'https://polluted.example',
      sampleCount: 20,
      availableSampleCount: 8,
      failedSampleCount: 12,
      readableSamples: 'Book A|Book B|Book C',
      message: 'SEARCH_MISMATCH=12',
    }).join('\t'),
    probeRow({
      index: 4,
      sourceName: 'Low Success Ratio Source',
      sourceUrl: 'https://low-ratio.example',
      readableSamples: 'Book A|Book B|Book C',
      sampleCount: 20,
      availableSampleCount: 8,
      failedSampleCount: 12,
    }).join('\t'),
    probeRow({
      index: 5,
      sourceName: 'Single Mainstream Hit Source',
      sourceUrl: 'https://single-hit.example',
      sampleCount: 20,
      availableSampleCount: 1,
      failedSampleCount: 19,
      readableSamples: 'Book A',
    }).join('\t'),
    probeRow({
      index: 6,
      sourceName: 'Published Short Source',
      sourceUrl: 'https://published-short.example',
      sampleCount: 20,
      availableSampleCount: 1,
      failedSampleCount: 19,
      readableSamples: 'Published Book',
      shortCatalogSampleCount: 1,
      shortCatalogSamples: 'Published Book',
    }).join('\t'),
    probeRow({
      index: 7,
      sourceName: 'Coverage Short Source',
      sourceUrl: 'https://coverage-short.example',
      sampleCount: 20,
      availableSampleCount: 1,
      failedSampleCount: 19,
      readableSamples: 'Coverage Book',
      shortCatalogSampleCount: 1,
      shortCatalogSamples: 'Coverage Book',
    }).join('\t'),
    probeRow({
      index: 8,
      status: 'METADATA_AVAILABLE',
      sourceName: 'Metadata Only Source',
      sourceUrl: 'https://metadata.example',
      readableSamples: 'Book A|Book B|Book C',
    }).join('\t'),
    probeRow({
      index: 9,
      status: 'CATALOG_FAILED',
      usable: 'false',
      sourceName: 'No Readable Preserved Source',
      sourceUrl: 'https://no-readable.example',
      readableSamples: '',
      availableSampleCount: 0,
      failedSampleCount: 3,
    }).join('\t'),
  ].join('\n')
);
fs.writeFileSync(
  runtimePreservePath,
  [
    'sourceUrl\tsourceName\ttier\tbucket\tscore\tevents\tlatestVerifiedGoodOrdinal\tbestRank\treasons\tbooks',
    'https://short.example\tShort Catalog Source\t1\tclassic\t9000\t1\t0\t1\truntime-front\tBook A',
    'https://no-readable.example\tNo Readable Preserved Source\t1\tclassic\t9000\t1\t0\t1\tlearned-score\tBook B',
  ].join('\n')
);

const result = spawnSync('node', [
  'tools/source-quality/select-final-sources.mjs',
  '--candidate', candidatePath,
  '--sample', samplePath,
  '--probeDir', probeDir,
  '--runtimePreserve', runtimePreservePath,
  '--output', outputPath,
  '--seed', seedPath,
  '--report', reportPath,
  '--summary', summaryPath,
  '--cap', '10',
], { cwd: repoRoot, encoding: 'utf8' });

assert.equal(result.status, 0, result.stderr);
const report = parseTsv(fs.readFileSync(reportPath, 'utf8'));
const shortSource = report.find(row => row.sourceUrl === 'https://short.example');
const oneShortSource = report.find(row => row.sourceUrl === 'https://one-short.example');
const goodSource = report.find(row => row.sourceUrl === 'https://good.example');
const pollutedSource = report.find(row => row.sourceUrl === 'https://polluted.example');
const lowRatioSource = report.find(row => row.sourceUrl === 'https://low-ratio.example');
const singleHitSource = report.find(row => row.sourceUrl === 'https://single-hit.example');
const publishedShortSource = report.find(row => row.sourceUrl === 'https://published-short.example');
const coverageShortSource = report.find(row => row.sourceUrl === 'https://coverage-short.example');
const metadataOnlySource = report.find(row => row.sourceUrl === 'https://metadata.example');
const noReadableSource = report.find(row => row.sourceUrl === 'https://no-readable.example');
assert.equal(shortSource?.tier, '3');
assert.equal(shortSource?.shortCatalogSampleCount, '2');
assert.equal(oneShortSource?.tier, '2');
assert.equal(oneShortSource?.shortCatalogSampleCount, '1');
assert.equal(goodSource?.tier, '1');
assert.equal(pollutedSource?.tier, '3');
assert.equal(lowRatioSource?.tier, '2');
assert.equal(singleHitSource?.tier, '3');
assert.equal(publishedShortSource?.tier, '2');
assert.equal(publishedShortSource?.shortCatalogSampleCount, '0');
assert.equal(coverageShortSource?.tier, '3');
assert.equal(coverageShortSource?.shortCatalogSampleCount, '0');
assert.equal(metadataOnlySource, undefined);
assert.equal(noReadableSource?.tier, '3');

function source(name, url) {
  return {
    bookSourceName: name,
    bookSourceUrl: url,
    enabled: true,
    sourceCompatibility: 'SOURCE_ENGINE',
  };
}

function probeHeader() {
  return [
    'index',
    'status',
    'usable',
    'tier',
    'score',
    'seedTier',
    'seedScore',
    'bucket',
    'sourceName',
    'sourceUrl',
    'enabled',
    'sampleKeyword',
    'sampleCount',
    'availableSampleCount',
    'failedSampleCount',
    'readableSamples',
    'shortCatalogSampleCount',
    'shortCatalogSamples',
    'readableSourceCountForSample',
    'rareReadable',
    'rareReadableKeywords',
    'searchCount',
    'bookName',
    'author',
    'bookUrl',
    'chapterCount',
    'freshnessHint',
    'duplicateCount',
    'missingRangeCount',
    'contentQuality',
    'contentCoherence',
    'contentLength',
    'durationMs',
    'searchMs',
    'detailMs',
    'catalogMs',
    'contentMs',
    'message',
  ];
}

function probeRow(overrides) {
  const row = {
    index: 0,
    status: 'AVAILABLE',
    usable: 'true',
    tier: 1,
    score: 9500,
    seedTier: 1,
    seedScore: 9500,
    bucket: 'classic',
    sourceName: '',
    sourceUrl: '',
    enabled: 'true',
    sampleKeyword: 'Book A',
    sampleCount: 3,
    availableSampleCount: 3,
    failedSampleCount: 0,
    readableSamples: '',
    shortCatalogSampleCount: 0,
    shortCatalogSamples: '',
    readableSourceCountForSample: 1,
    rareReadable: 'false',
    rareReadableKeywords: '',
    searchCount: 3,
    bookName: 'Book A',
    author: 'Author',
    bookUrl: 'https://example/book',
    chapterCount: 300,
    freshnessHint: 300,
    duplicateCount: 0,
    missingRangeCount: 0,
    contentQuality: 95,
    contentCoherence: 95,
    contentLength: 2000,
    durationMs: 3000,
    searchMs: 300,
    detailMs: 300,
    catalogMs: 300,
    contentMs: 300,
    message: 'readable=3/3',
    ...overrides,
  };
  return probeHeader().map(key => String(row[key] ?? ''));
}

function parseTsv(text) {
  const lines = text.trim().split(/\r?\n/);
  const header = lines.shift().split('\t');
  return lines.map(line => {
    const cells = line.split('\t');
    return Object.fromEntries(header.map((key, index) => [key, cells[index] ?? '']));
  });
}
