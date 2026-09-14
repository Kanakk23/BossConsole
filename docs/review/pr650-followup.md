# PR #650 review follow-up

This change addresses the four blocking findings in the [maintainer review](https://github.com/risa-labs-inc/BossConsole/pull/650#issuecomment-5663173004).
The companion transport consumer is updated in [fluck-browser #45](https://github.com/risa-labs-inc/boss-plugin-fluck-browser/pull/45).

## Changes and evidence

| Finding | Change | Regression coverage |
| --- | --- | --- |
| Native/page cancellation differs | Native code retains leading vertical travel but evaluates cancellation only on horizontal samples, as the page does. Commit qualification waits for three horizontal samples; zero-crossing reversal matches the page. | Shared sample fixtures run through the actual native reducer and page script, including leading noise, vertical curl, easing, reversal, post-threshold drift, short flick and cancellation. Constants are pinned across languages. |
| Element blur cancels a swipe | Only a blur targeted at the window resets the detector. | Element focus transfer commits; window blur and hidden-document transitions cancel. |
| Off switch and failure lifecycle | Startup binds observation to the effective setting. Native setup/poll/cleanup runs on a daemon worker. Disabled sessions cancel immediately; their callbacks cannot join a replacement session. Setup failures release partial resources, and run-loop failures cancel and permit retry. | Disabled startup, queued startup cancellation, disable/re-enable, denied permission, run-loop return/exception, partial allocation, replacement, tap interruption and concurrent claim/release tests. Settings descriptions distinguish permission denial from failure, including environment-owned settings. |
| Wheel hot path and lossy companion transport | Disabled and line-mode wheels avoid host IPC. Vertical pixel samples still retain initial scroll ownership. Active publication occurs once per contact. The host retains 32 terminal records, which the companion reconciles before a new pointer event or watchdog poll can discard the old contact. | Existing nested-scroller tests retained. Publication-count and bounded-history tests cover rapid contacts; companion tests cover recovery, cancellation, missing/evicted evidence and stale watchdog ownership. |

Terminal history is a bounded, non-destructive record, not an unbounded delivery queue. A consumer delayed past 32 subsequent releases cancels if its record has been evicted. Updated companion code also recognizes that these hosts cancel on failure themselves, so the old ten-second watchdog does not cancel a stationary native hold.

## Other review observations

- Non-macOS regression and iframe release were ruled out by the maintainer: both settings and injection are macOS-gated, and the detector skips subframes. These gates remain intact.
- Nested horizontal scrollers retain the contact even at an edge, and momentum is excluded. Both are deliberate requirements and remain tested.
- No speculative changes were made to native delta units, the 1 ms renderer timestamp tolerance, or initial direction thresholds. The native/page direction policies now agree; the early-timestamp acceptance boundary is tested. Hardware calibration is still required below.
- The 400 ms same-direction host gate remains conservative across document replacement. Its comments no longer refer to the removed inactivity timer. It also limits repeated page-originated bridge calls; two intentional same-direction requests inside the window can still be refused.
- Native snapshot claims now consult `observable`. A replacement Begin/MayBegin cancels prior claimants. Source/tap resources and callback retention have explicit cleanup.
- The production bridge still supplies its native claim. Its nullable default remains useful to existing bridge-only tests; this was not classified as a production defect by the maintainer.
- The existing JNA boolean mapping remains unchanged; the maintainer requested evidence before speculative ABI changes.
- Duplicate/stale releases, unavailable observation and visibility reset have page regressions. The host-generated release statement itself is executed by the cross-layer suite.

## Validation

Local checks:

- `node scripts/test/test-swipe-nav.js`
- `:composeApp:desktopTest` restricted to BrowserSwipeNavTest, MacOSScrollGesturePhasesTest, ScrollPhaseObserverTest, SwipeNavParityTest and SwipeNavSettingsTest
- `:composeApp:detekt :composeApp:ktlintCheck`
- Companion: `CI=true ./gradlew test buildPluginJar` using its downloaded API dependency, matching CI

GitHub CI must pass for the final pushed commits. Local automated results do not establish physical trackpad behavior.

## Required physical macOS check

Still to be performed on the paired host/plugin build; no new hardware validation is claimed:

- Hold a completed swipe stationary, including beyond ten seconds: no navigation until release.
- Release a qualifying swipe: one navigation; repeat in both directions.
- Ease below the threshold and reverse through zero: no navigation.
- Try a short flick with a large momentum tail: momentum must not supply missing finger travel.
- Scroll nested horizontal content from its interior and either edge: retain ownership throughout.
- Repeat on the browser home surface, including rapid successive contacts while navigation is busy.
- Compare affordance progress and release behavior for slow/fractional drags and browser zoom. Native points, CSS pixels and Compose units remain different coordinate spaces; the companion's existing calibration is preserved.
- Disable/re-enable in Settings; where applicable, verify denied Input Monitoring and recovery after granting it.

The reviewer requested this check before merge. Keep that requirement visible rather than treating green automated tests as a substitute.
