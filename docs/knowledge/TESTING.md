# Testing register

| Area | How tested | Evidence level |
|---|---|---|
| Recognition state machine, engine selection, token client | JVM unit tests with fake engines/HTTP, virtual time | Automated, deterministic |
| Wikipedia/Wikidata parsing and matching | JVM unit tests on hand-built fixtures shaped like the documented API responses | Fixture-level (not real data) |
| Same, against real APIs | `LiveWikiSmokeTest` (opt-in `LIVE_WIKI=1`), run by CI job `live-wiki` | Real network, informational |
| Cache/offline behaviour | Unit tests with scripted failures | Automated |
| History/favorites/cache persistence | Robolectric + in-memory Room | JVM, real SQLite |
| External links and safety allow-list | Robolectric | Automated |
| Full UI flow and screenshots | Robolectric native graphics on the real `MainActivity` | JVM rendering, **not a device** |
| Asset-slot manifest consistency | Unit test | Automated |
| Microphone capture, ShazamKit, real devices, TalkBack, performance, accuracy | **Not tested** | None |
