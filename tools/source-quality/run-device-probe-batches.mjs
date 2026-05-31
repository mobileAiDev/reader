import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = process.cwd();
const args = parseArgs(process.argv.slice(2));
const adb = args.adb ?? 'adb';
const packageName = args.packageName ?? 'com.ldp.reader';
const activity = args.activity ?? '.ui.activity.SourceEngineActivity';
const candidatePath = path.resolve(repoRoot, args.candidate ?? 'tools/source-quality/candidates/source-candidates-deduped.json');
const samplePath = path.resolve(repoRoot, args.sample ?? 'tools/source-quality/source-quality-sample-books.json');
const runId = args.runId ?? timestamp();
const outputDir = path.resolve(repoRoot, args.outputDir ?? `build/source-quality/probe-runs/${runId}`);
const batchSize = toPositiveInt(args.batchSize, 20);
const concurrency = toPositiveInt(args.concurrency ?? args.maxConcurrentSources, 1);
const startOffset = toNonNegativeInt(args.startOffset, 0);
const endOffsetArg = args.endOffset == null ? null : toNonNegativeInt(args.endOffset, 0);
const timeoutMs = toPositiveInt(args.timeoutMs, 30 * 60 * 1000);
const pollMs = toPositiveInt(args.pollMs, 5000);
const maxBooksPerSource = toPositiveInt(args.maxBooksPerSource, 1);
const maxContentSamples = toPositiveInt(args.maxContentSamples, 3);
const sampleSet = args.sampleSet ?? 'selectionSample';
const dryRun = args.dryRun === 'true';
const zeroOnly = args.zeroOnly === 'true';

fs.mkdirSync(outputDir, { recursive: true });
const candidateJson = fs.readFileSync(candidatePath);
const sampleKeywords = readSampleKeywords(samplePath, sampleSet);
const sampleText = `${sampleKeywords.join('\n')}\n`;

console.error(`runId=${runId}`);
console.error(`outputDir=${path.relative(repoRoot, outputDir).replaceAll('\\', '/')}`);
console.error(`candidate=${path.relative(repoRoot, candidatePath).replaceAll('\\', '/')}`);
console.error(`sampleSet=${sampleSet}`);
console.error(`sampleCount=${sampleKeywords.length}`);
console.error(`batchSize=${batchSize}`);
console.error(`concurrency=${concurrency}`);

if (dryRun) {
  process.exit(0);
}

pushAppPrivateFile('files/source-engine-lab/book-sources.json', candidateJson);
pushAppPrivateFile('files/source-engine-lab/sample-keywords.txt', Buffer.from(sampleText, 'utf8'));

const zeroReport = runBatch(0, 0, 'zero');
const compatibleTotal = parseCompatibleTotal(zeroReport.summaryText);
const endOffset = endOffsetArg ?? compatibleTotal;
console.error(`compatibleTotal=${compatibleTotal}`);
console.error(`startOffset=${startOffset}`);
console.error(`endOffset=${endOffset}`);

if (zeroOnly) {
  process.exit(0);
}

for (let offset = startOffset; offset < endOffset; offset += batchSize) {
  const size = Math.min(batchSize, endOffset - offset);
  runBatch(offset, size, `batch-${String(offset).padStart(6, '0')}`);
}

function runBatch(offset, maxSources, label) {
  const summaryOut = path.join(outputDir, `${label}.txt`);
  const tsvOut = path.join(outputDir, `${label}.tsv`);
  removeLatestReports();
  forceStop();
  launchActivity(offset, maxSources);
  const summaryText = waitForSummary(offset, maxSources);
  const tsvText = readAppFile('files/source-engine-lab/reports/source-quality-lab-latest.tsv');
  fs.writeFileSync(summaryOut, summaryText, 'utf8');
  fs.writeFileSync(tsvOut, tsvText, 'utf8');
  const parsed = parseSummary(summaryText);
  console.error([
    label,
    `offset=${offset}`,
    `maxSources=${maxSources}`,
    `probed=${parsed.probed ?? '?'}`,
    `available=${parsed.available ?? '?'}`,
    `failed=${parsed.failed ?? '?'}`,
    `bookSamples=${parsed.bookSamples ?? '?'}`,
  ].join('\t'));
  return { summaryText, tsvText };
}

function pushAppPrivateFile(file, input) {
  runAdbShell(
    `run-as ${shellQuote(packageName)} sh -c ${shellQuote(
      `mkdir -p ${shellQuote(path.posix.dirname(file))} && cat > ${shellQuote(file)}`
    )}`,
    { input }
  );
}

function removeLatestReports() {
  runAdbShell(
    `run-as ${shellQuote(packageName)} sh -c ${shellQuote(
      'rm -f files/source-engine-lab/reports/source-quality-lab-latest.txt files/source-engine-lab/reports/source-quality-lab-latest.tsv'
    )}`
  );
}

function forceStop() {
  runAdb(['shell', 'am', 'force-stop', packageName]);
}

function launchActivity(offset, maxSources) {
  runAdb([
    'shell',
    'am',
    'start',
    '-n',
    `${packageName}/${activity}`,
    '--ez',
    'sourceQualityAutoRun',
    'true',
    '--es',
    'sourceQualityMode',
    'lab',
    '--ei',
    'sourceQualitySourceOffset',
    String(offset),
    '--ei',
    'sourceQualityMaxSources',
    String(maxSources),
    '--ei',
    'sourceQualityMaxConcurrentSources',
    String(concurrency),
    '--ei',
    'sourceQualityMaxBooksPerSource',
    String(maxBooksPerSource),
    '--ei',
    'sourceQualityMaxContentSamples',
    String(maxContentSamples),
  ]);
}

function waitForSummary(offset, maxSources) {
  const start = Date.now();
  const expected = `sourceOffset=${offset} maxSources=${maxSources}`;
  let lastError = '';
  while (Date.now() - start < timeoutMs) {
    const result = runAdbQuiet([
      'exec-out',
      'run-as',
      packageName,
      'cat',
      'files/source-engine-lab/reports/source-quality-lab-latest.txt',
    ]);
    if (result.status === 0) {
      const text = result.stdout.toString('utf8');
      if (text.includes(expected) && text.includes('Source quality lab')) {
        return text;
      }
      lastError = `latest summary exists but does not match ${expected}`;
    } else {
      lastError = result.stderr.toString('utf8').trim() || `adb status ${result.status}`;
    }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, pollMs);
  }
  throw new Error(`timed out waiting for report ${expected}; ${lastError}`);
}

function readAppFile(file) {
  return runAdb(['exec-out', 'run-as', packageName, 'cat', file]).stdout.toString('utf8');
}

function parseCompatibleTotal(summaryText) {
  const parsed = parseSummary(summaryText);
  const imported = toNumber(parsed.imported);
  const disabled = toNumber(parsed.disabled);
  const incompatible = toNumber(parsed.incompatible);
  return Math.max(0, imported - disabled - incompatible);
}

function parseSummary(text) {
  const output = {};
  for (const line of text.split(/\r?\n/)) {
    for (const match of line.matchAll(/([A-Za-z]+)=([^\s]+)/g)) {
      output[match[1]] = match[2];
    }
  }
  return output;
}

function readSampleKeywords(file, key) {
  const json = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
  const rows = json[key] ?? json.selectionSample ?? json.smokeSample;
  if (!Array.isArray(rows) || rows.length === 0) {
    throw new Error(`no sample rows for ${key} in ${file}`);
  }
  return rows
    .map(row => String(row.title ?? '').trim())
    .filter(Boolean)
    .filter((title, index, all) => all.indexOf(title) === index);
}

function runAdb(args, options = {}) {
  const result = runAdbQuiet(args, options);
  if (result.status !== 0) {
    throw new Error([
      `${adb} ${args.join(' ')}`,
      result.stdout.toString('utf8'),
      result.stderr.toString('utf8'),
    ].filter(Boolean).join('\n'));
  }
  return result;
}

function runAdbShell(command, options = {}) {
  return runAdb(['shell', command], options);
}

function runAdbQuiet(adbArgs, options = {}) {
  return spawnSync(adb, adbArgs, {
    input: options.input,
    maxBuffer: 256 * 1024 * 1024,
  });
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

function toNumber(value) {
  const number = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(number) ? number : 0;
}

function toPositiveInt(value, fallback) {
  const number = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

function toNonNegativeInt(value, fallback) {
  const number = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(number) && number >= 0 ? number : fallback;
}

function timestamp() {
  return new Date().toISOString().replaceAll(':', '').replace(/\.\d+Z$/, 'Z');
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
