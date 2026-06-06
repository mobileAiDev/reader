# Source Search Function Test Results

Date: 2026-06-05

Device/app:

- Device: OnePlus PKR110, Android 36.
- App: `com.ldp.reader` debug, version `1.0`.
- Bridge: `ai_app_bridge` `0.2.8`.

Scope:

- Safe novel search UI path only.
- Sensitive/rare titles remain metadata-only and were not opened here.
- Results are from the real `SearchActivity` with bridge events/tree.

## Build And Unit Baseline

Before this smoke pass:

- `.\gradlew.bat :app:testDebugUnitTest --tests "com.ldp.reader.source.SourceEngineReaderContentProviderTest"` passed.
- `.\gradlew.bat :app:assembleDebug` passed.
- `app-debug.apk` was installed on device.

Latest focused regression build:

- Focused search/reader/cover tests passed, including progressive merge,
  contained-title grouping, exact-title related-group handling, reader
  no-parcel-catalog guard, and cover URL fallback tests.
- `.\gradlew.bat :app:assembleDebug` passed and the APK was installed on the
  device.

## Latest Focused Regression Result

`叩问仙道` after the final fixes:

- Search source window: `selected=273`, tier window
  `tier1=54`, `tier2=205`, `tier3=14`.
- First provider publish: `3709ms`, `raw=49`, `output=1`,
  `requestsStarted=54`, `completed=35`.
- First two-result UI publish was around `12.4s`; final search state was
  `raw=821`, `count=2`, `durationMs=33235`.
- Top final candidate was the long author group with `2734` chapters and cover
  evidence. The 50-chapter same-title group remained as a separate lower result
  instead of replacing the long result.
- Detail page rendered with `coverUsable=true`, `coverCandidates=2`,
  `chapters=2734`; detail preview resolved from session cache in `523ms`.
- Reader entry did not send the full catalog through `Intent`; source-engine
  session cache hit `2734` chapters in `3ms`, loaded the reader catalog in
  `112ms`, and opened/parsing the first page in `53ms`.
- Exiting the uncollected reader showed the existing add-to-shelf confirmation.
  After cancelling, `MainActivity` bookshelf was empty, `叩问仙道` matched zero
  visible nodes, and the shelf edit action remained disabled.

The remaining slow part is no longer first read-open. It is the full search
waterfall and background tier/V8 work: fast valid search responses were present
in `251-480ms`, but slow tail sources still took `8.5-18.2s`, and completed
search waits for all 273 selected compatible sources.

## P0 Live Smoke Results

| Title | Result | First visible/ranked timing | Selected reading candidate evidence | Notes |
| --- | --- | --- | --- | --- |
| `叩问仙道` | PASS quality, WARN performance | First provider publish `3709ms`; first two-result UI publish around `12.4s`; completed search `33.2s`. | Final top candidate is the long author group with `2734` chapters and cover evidence. | The 50-chapter group remains a separate lower result; it no longer replaces or outranks the long candidate. Reader first-open used session cache, not `Intent` catalog. |
| `诡秘之主` | PASS quality, FAIL performance | `source_search_ui_publish elapsedMs_10678`. | `免费小说`/`笔趣阁` long candidates around `1224-1227` chapters. | Search layer already had `125` requests by first publish; slow search hosts reached `8.0s`. |
| `凡人修仙传` | PASS quality, FAIL performance | `source_search_ui_publish elapsedMs_8978`. | `领域小说` `3074` chapters. | Detail-only/zero-catalog candidates did not become reading source; unrelated `不祥` author group was rejected. |
| `三体` | PASS quality, WARN performance | Exact first publish not captured cleanly because bridge output window rolled; UI result visible. Completed stage was still running around `56.8s`. | Progress selected `九九藏书网` `82` chapters; completed stage selected `落霞小说` `152` chapters. | Short-book case is not falsely rejected. Completed stage filtered short/weak catalogs below threshold `100`; several non-target groups timed out around `39.9s`. |
| `夜无疆` | PASS quality, FAIL performance | First captured ranked result `elapsedMs_9785`; later ranked `15919ms`. | `领域小说` `705` chapters. | `qbiqus` `19`-chapter candidate was filtered; no short catalog selected. |
| `我在精神病院学斩神` | PASS quality, FAIL performance | First captured ranked result `elapsedMs_23322`; later ranked `39700ms`. | `领域小说` `2037` chapters; also `棉花糖` `2032` chapters. | `23`, `100`, and other short catalogs were filtered. This broad sample is the clearest slow case: raw candidates exceeded `1400` before first captured rank and later exceeded `2100`. |
| `青山` | PASS top-rank quality, FAIL performance | First captured ranked result `elapsedMs_18709`; later ranked `22283ms`. | Top candidate was `棉花糖` `716` chapters for author `会说话的肘子`. | Short-title ambiguity produced two ranked same-title groups, but the target author stayed first. `qbiqus` short catalog was filtered; raw candidates exceeded `1700` before first captured rank. |
| `苟在两界修仙` | PASS quality, FAIL performance | First captured ranked result `elapsedMs_8326`; later ranked `15195ms`; a late completed pass was still around `60.5s`. | `棉花糖` `513` chapters. | `qbiqus` `20`-chapter candidate was filtered. T2 already had a usable result by `8.3s`, but completed/content work continued with many more raw candidates. |

## Findings

Quality gates are working on the tested samples:

- Known long books did not pick 20/50/100-chapter partial catalogs.
- Short/published books such as `三体` were still allowed to publish with a plausible shorter catalog.
- `m.bbiquge*` did not appear as selected reading candidate in these live runs.
- Short-catalog filtering is visible in logs and is correcting bad candidates after validation.

Performance is still not acceptable:

- Common samples are still roughly `8-19s` to first visible or ranked result.
- Broad samples can reach `23s+` before first captured ranked result.
- The delay is not mainly one slow detail/catalog request. It is the whole progressive search wave accumulating hundreds of source requests before rank/publish.
- Network summaries repeatedly show many search-layer requests plus slow tails:
  `183.192.65.101`, `www.bqgz.cc`, `www.deqixs.org`,
  `www.xshuquge.net`, `www.shuqusk.info`, `www.77shuku.org`,
  and similar hosts.
- Queue/global wait exists but is usually smaller than slow source tail and raw-candidate expansion. Examples: search `qAvg` commonly `49-222ms`, while slow hosts hit `6-24s`.
- Full waterfall/content-tier work can continue for tens of seconds after the first result. This is fine if fully isolated from UI, but it is risky if it competes with the next search.

## Test Hygiene Notes

- For novel-only P0, explicitly select the `小说` tab before each run. One pass accidentally entered the comic search/read path after the page was in media state, so that run was discarded for novel results.
- Bridge `events` output is a rolling window. For long searches, capture immediately after each phase or use narrower event logging; otherwise `source_search_ui_publish` can roll out of the returned page.

## Follow-Up Test Matrix

P0 safe novel smoke samples are complete for this pass.

Then run P1 as batches:

- Classic long books.
- Current/high-coverage books.
- Fanqie/breadth books.
- Runtime-front regression books.
- Published/category short books.

Sensitive rare samples remain metadata-only:

- `琼明神女录`
- `六朝清羽记`
- `逍遥小散仙`
- `仙子的修行`
