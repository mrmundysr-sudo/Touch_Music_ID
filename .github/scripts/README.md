# TestFlight tester management worker

`testflight_tester_admin.py` is run by `.github/workflows/testflight-testers.yml`. It is **not** a signing or upload
workflow and cannot reach App Store review endpoints (see `ALLOWED` in the script).

This repository is public, so the request and the answer are encrypted. The only new secret is
`TESTFLIGHT_PAYLOAD_PRIVATE_KEY`, created by the Android app (Touch Developer > TestFlight testers > Set up encryption key).
The existing `APP_STORE_CONNECT_API_*` secrets are read as-is and are unchanged.

Offline tests (no network, no secrets): `cd .github/scripts && python -m unittest test_testflight_tester_admin`
