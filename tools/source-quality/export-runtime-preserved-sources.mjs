import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = process.cwd();
const args = parseArgs(process.argv.slice(2));
const adb = args.adb ?? 'adb';
const packageName = args.packageName ?? 'com.ldp.reader';
const activity = args.activity ?? '.ui.activity.SourceEngineActivity';
const candidatePath = path.resolve(repoRoot, args.candidate ?? 'tools/source-quality/candidates/source-candidates-deduped.json');
const outputPath = path.resolve(repoRoot, args.output ?? 'build/source-quality/runtime-preserved-sources.tsv');
const timeoutMs = toPositiveInt(args.timeoutMs, 120000);
const pollMs = toPositiveInt(args.pollMs, 2000);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
pushAppPrivateFile('files/source-engine-lab/book-sources.json', fs.readFileSync(candidatePath));
removeRuntimePreserveFile();
forceStop();
launchActivity();
const text = waitForRuntimePreserveFile();
fs.writeFileSync(outputPath, text, 'utf8');

const rowCount = Math.max(0, text.split(/\r?\n/).filter(Boolean).length - 1);
console.error(`candidate=${path.relative(repoRoot, candidatePath).replaceAll('\\', '/')}`);
console.error(`output=${path.relative(repoRoot, outputPath).replaceAll('\\', '/')}`);
console.error(`preserved=${rowCount}`);

function pushAppPrivateFile(file, input) {
  runAdbShell(
    `run-as ${shellQuote(packageName)} sh -c ${shellQuote(
      `mkdir -p ${shellQuote(path.posix.dirname(file))} && cat > ${shellQuote(file)}`
    )}`,
    { input }
  );
}

function removeRuntimePreserveFile() {
  runAdbShell(
    `run-as ${shellQuote(packageName)} sh -c ${shellQuote(
      'rm -f files/source-engine-lab/runtime-preserved-sources.tsv'
    )}`
  );
}

function forceStop() {
  runAdb(['shell', 'am', 'force-stop', packageName]);
}

function launchActivity() {
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
    'runtime-preserve',
  ]);
}

function waitForRuntimePreserveFile() {
  const start = Date.now();
  let lastError = '';
  while (Date.now() - start < timeoutMs) {
    const result = runAdbQuiet([
      'exec-out',
      'run-as',
      packageName,
      'cat',
      'files/source-engine-lab/runtime-preserved-sources.tsv',
    ]);
    if (result.status === 0) {
      const text = result.stdout.toString('utf8');
      if (text.startsWith('sourceUrl\tsourceName\t')) return text;
      lastError = 'runtime preserve file exists but header is incomplete';
    } else {
      lastError = result.stderr.toString('utf8').trim() || `adb status ${result.status}`;
    }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, pollMs);
  }
  throw new Error(`timed out waiting for runtime preserve export; ${lastError}`);
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

function toPositiveInt(value, fallback) {
  const number = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(number) && number > 0 ? number : fallback;
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
