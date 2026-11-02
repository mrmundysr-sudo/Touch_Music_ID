# Privacy and data flow (draft for owner review)

Closes the "privacy disclosure" open question only as a **draft**; wording and any store listing disclosure need owner/legal review.

| Data | Where it goes | Kept? |
|---|---|---|
| Microphone audio | Only while the user has tapped Listen and a **live** engine is running. Today no live engine exists. Demo mode records nothing | Never written to disk by this app. In memory only until the match call returns |
| Audio fingerprint (future, ShazamKit) | Sent to Apple by the ShazamKit SDK to match | Apple's handling is governed by Apple's terms; **disclose in the store listing before release** |
| Song title + artist | Sent to `en.wikipedia.org` and `www.wikidata.org` as search text (HTTPS) | Cached locally as facts for up to 7 days (1 day for "no match") |
| History, favorites | Local Room database on the phone | Until the user deletes them. Not backed up (`allowBackup=false`) |
| Settings (demo-mode switch) | Local SharedPreferences | Local |
| Developer token | From the owner's token service (future) | Memory only |
| Analytics / ads / accounts / identifiers | **None** | n/a |

Network requests carry a descriptive `User-Agent` (`TouchMusicID/0.1 (...)`) as Wikimedia asks; that string contains no user data.
Wikipedia/Wikidata see the device IP like any web request. Wikipedia text is shown with attribution (CC BY-SA 4.0) and links.

Permissions: `INTERNET`, `RECORD_AUDIO` (requested at runtime only when a live engine needs it).
External links open in the user's browser/app via `ACTION_VIEW`; only https links to allow-listed hosts are opened.
