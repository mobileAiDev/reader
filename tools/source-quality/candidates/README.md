# Source Candidate JSON

This directory stores the single deduplicated source-candidate pool used by the
source-quality selection workflow.

Committed file:

```text
source-candidates-deduped.json
```

Do not commit raw upstream source packages here. Raw downloads are temporary
build artifacts under:

```text
build/source-quality/raw/
```

Regenerate the deduped pool with:

```text
node tools/source-quality/fetch-source-candidates.mjs
node tools/source-quality/merge-source-candidates.mjs
```

Default outputs:

```text
tools/source-quality/candidates/source-candidates-deduped.json
build/source-quality/source-candidates-report.tsv
```

Do not promote this candidate pool directly into app assets. It is only the
input pool for Android-device probing and final tiered selection.

After device probe TSVs exist, generate the final selected source JSON with:

```text
node tools/source-quality/export-runtime-preserved-sources.mjs
node tools/source-quality/select-final-sources.mjs \
  --probeDir build/source-quality/probe-runs/full-selection-20260531 \
  --runtimePreserve build/source-quality/runtime-preserved-sources.tsv
```

The generated app asset is the evidence set, not a fixed-size slice of the full
candidate pool. The 2026-05-31 run produced 319 app sources: 272 probe-readable
sources plus 47 runtime-preserved sources from real bookshelf evidence. Eleven
runtime-preserved but currently engine-incompatible sources are kept at the end
as tier 3, low-score fallback rows.

Use `--apply true` only after reviewing the generated summary/report.
