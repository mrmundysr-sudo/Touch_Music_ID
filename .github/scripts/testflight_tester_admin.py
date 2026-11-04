#!/usr/bin/env python3
"""TestFlight tester management worker (no signing, no uploads, no App Review).

Runs inside .github/workflows/testflight-testers.yml. This repository is PUBLIC, so:

* The request arrives as ciphertext (RSA-OAEP-wrapped AES-256-GCM) encrypted to a public
  key held by the phone. Only the private key in the TESTFLIGHT_PAYLOAD_PRIVATE_KEY secret
  can open it.
* The answer is encrypted to a one-time public key that is inside the request, so only the
  phone that asked can read it. It is printed as ciphertext lines (TFMRES1 ...).
* Nothing decrypted is ever printed, written to the step summary, or uploaded as an
  artifact. Errors are reported as short fixed codes (TFMERR1 ...) only.

Apple access uses the existing App Store Connect API secrets through environment variables.
Only an allowlist of TestFlight endpoints can be called; App Store review submission
endpoints are deliberately not on it.
"""
import base64
import hashlib
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, utils
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ASC_BASE = "https://api.appstoreconnect.apple.com"
MAX_AGE_SECONDS = 900
MAX_EMAILS = 50
CHUNK = 3000
AAD_REQUEST = b"tfm1-req"

OAEP = padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()), algorithm=hashes.SHA256(), label=None)

# (method, path regex). Anything else is refused before any network call is made.
ALLOWED = [
    ("GET", r"^/v1/apps$"),
    ("GET", r"^/v1/apps/[A-Za-z0-9._-]+/betaGroups$"),
    ("GET", r"^/v1/builds$"),
    ("GET", r"^/v1/builds/[A-Za-z0-9._-]+$"),
    ("GET", r"^/v1/builds/[A-Za-z0-9._-]+/buildBetaDetail$"),
    ("GET", r"^/v1/builds/[A-Za-z0-9._-]+/betaAppReviewSubmission$"),
    ("GET", r"^/v1/betaGroups/[A-Za-z0-9._-]+$"),
    ("GET", r"^/v1/betaGroups/[A-Za-z0-9._-]+/betaTesters$"),
    ("GET", r"^/v1/betaTesters$"),
    ("POST", r"^/v1/betaGroups$"),
    ("POST", r"^/v1/betaGroups/[A-Za-z0-9._-]+/relationships/builds$"),
    ("POST", r"^/v1/betaGroups/[A-Za-z0-9._-]+/relationships/betaTesters$"),
    ("DELETE", r"^/v1/betaGroups/[A-Za-z0-9._-]+/relationships/betaTesters$"),
    ("POST", r"^/v1/betaTesters$"),
    ("POST", r"^/v1/betaTesterInvitations$"),
    ("POST", r"^/v1/betaAppReviewSubmissions$"),
]

EMAIL_RE = re.compile(r"^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")


class Stop(Exception):
    """Fatal, safe-to-print failure. Message is a fixed code, never user data."""


def b64d(s):
    return base64.b64decode(s.encode("ascii"), validate=True)


def b64e(b):
    return base64.b64encode(b).decode("ascii")


def public_key_id(public_key):
    der = public_key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(der).hexdigest()[:16]


# ---------------------------------------------------------------- crypto

def open_request(envelope_text, private_key):
    try:
        env = json.loads(envelope_text)
        if env.get("v") != 1:
            raise Stop("bad_envelope")
        aes_key = private_key.decrypt(b64d(env["k"]), OAEP)
        plain = AESGCM(aes_key).decrypt(b64d(env["n"]), b64d(env["c"]), AAD_REQUEST)
        return json.loads(zlib.decompress(plain, 0, 1 << 20).decode("utf-8"))
    except Stop:
        raise
    except Exception:
        raise Stop("decrypt_failed")


def seal_response(result, response_public_der, rid):
    pub = serialization.load_der_public_key(response_public_der)
    aes_key = AESGCM.generate_key(bit_length=256)
    nonce = secrets.token_bytes(12)
    body = zlib.compress(json.dumps(result, separators=(",", ":")).encode("utf-8"), 9)
    ct = AESGCM(aes_key).encrypt(nonce, body, ("tfm1-res:" + rid).encode("ascii"))
    env = {"v": 1, "rid": rid, "k": b64e(pub.encrypt(aes_key, OAEP)), "n": b64e(nonce), "c": b64e(ct)}
    return b64e(json.dumps(env, separators=(",", ":")).encode("ascii"))


# ---------------------------------------------------------------- Apple client

class Asc:
    def __init__(self, key_id, issuer_id, private_pem, base=ASC_BASE):
        self.key_id = key_id
        self.issuer = issuer_id
        self.key = serialization.load_pem_private_key(private_pem.encode("utf-8"), password=None)
        self.base = base
        self._token = None
        self._token_exp = 0

    def _jwt(self):
        now = int(time.time())
        if self._token and now < self._token_exp - 60:
            return self._token
        header = {"alg": "ES256", "kid": self.key_id, "typ": "JWT"}
        claims = {"iss": self.issuer, "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"}
        signing = ".".join(base64.urlsafe_b64encode(json.dumps(p, separators=(",", ":")).encode()).rstrip(b"=").decode()
                           for p in (header, claims))
        der = self.key.sign(signing.encode("ascii"), ec.ECDSA(hashes.SHA256()))
        r, s = utils.decode_dss_signature(der)
        sig = base64.urlsafe_b64encode(r.to_bytes(32, "big") + s.to_bytes(32, "big")).rstrip(b"=").decode()
        self._token, self._token_exp = signing + "." + sig, now + 900
        return self._token

    def call(self, method, path, query=None, body=None):
        if not any(m == method and re.match(rx, path) for m, rx in ALLOWED):
            raise Stop("blocked_endpoint")
        url = self.base + path + ("?" + urllib.parse.urlencode(query, safe="[],") if query else "")
        data = json.dumps(body).encode("utf-8") if body is not None else None
        for attempt in range(3):
            req = urllib.request.Request(url, data=data, method=method)
            req.add_header("Authorization", "Bearer " + self._jwt())
            req.add_header("Accept", "application/json")
            if data is not None:
                req.add_header("Content-Type", "application/json")
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    raw = resp.read()
                    return resp.status, (json.loads(raw) if raw else None)
            except urllib.error.HTTPError as e:
                raw = e.read()
                if method == "GET" and e.code in (429, 500, 502, 503) and attempt < 2:
                    time.sleep(2 * (attempt + 1))
                    continue
                try:
                    return e.code, json.loads(raw) if raw else None
                except ValueError:
                    return e.code, None
            except (urllib.error.URLError, TimeoutError):
                if method == "GET" and attempt < 2:
                    time.sleep(2 * (attempt + 1))
                    continue
                raise Stop("apple_unreachable")
        raise Stop("apple_unreachable")


def apple_error(status, doc, stage=None):
    errs = []
    for e in (doc or {}).get("errors", [])[:3]:
        errs.append({k: str(e.get(k, ""))[:400] for k in ("status", "code", "title", "detail")})
    out = {"ok": False, "error": {"status": status, "errors": errs}}
    if stage:
        out["error"]["stage"] = stage
    if status == 401:
        out["error"]["hint"] = "Apple rejected the API key. Check the three App Store Connect secrets."
    elif status == 403:
        out["error"]["hint"] = ("The API key's role may not allow this. TestFlight tester and group "
                                "management needs a key with the Admin or App Manager role.")
    return out


def attrs(item):
    return (item or {}).get("attributes", {}) or {}


def rel_id(item, name):
    d = ((item or {}).get("relationships", {}).get(name, {}) or {}).get("data")
    return d.get("id") if isinstance(d, dict) else None


def paged(asc, path, query):
    items, pages = [], 0
    status, doc = asc.call("GET", path, query)
    while True:
        if status != 200:
            return status, doc, items
        items.extend(doc.get("data", []))
        pages += 1
        nxt = (doc.get("links") or {}).get("next")
        if not nxt or pages >= 5:
            return 200, doc, items
        cursor = urllib.parse.parse_qs(urllib.parse.urlparse(nxt).query).get("cursor", [None])[0]
        if not cursor:
            return 200, doc, items
        q = dict(query)
        q["cursor"] = cursor
        status, doc = asc.call("GET", path, q)


def tester_view(t):
    a = attrs(t)
    return {"id": t["id"], "email": a.get("email") or "", "firstName": a.get("firstName") or "",
            "lastName": a.get("lastName") or "", "state": a.get("state") or "", "inviteType": a.get("inviteType") or ""}


def safe_id(value):
    if not isinstance(value, str) or not re.match(r"^[A-Za-z0-9._-]{1,64}$", value):
        raise Stop("bad_request")
    return value


# ---------------------------------------------------------------- operations

def op_ping(asc, a):
    status, doc = asc.call("GET", "/v1/apps", {"limit": "1"})
    if status != 200:
        return apple_error(status, doc)
    return {"ok": True, "data": {"apple": "ok"}}


def op_apps(asc, a):
    status, doc, items = paged(asc, "/v1/apps", {"limit": "200", "fields[apps]": "name,bundleId,sku"})
    if status != 200:
        return apple_error(status, doc)
    return {"ok": True, "data": {"apps": [
        {"id": i["id"], "name": attrs(i).get("name", ""), "bundleId": attrs(i).get("bundleId", ""),
         "sku": attrs(i).get("sku", "")} for i in items]}}


def op_overview(asc, a):
    app_id = safe_id(a.get("appId"))
    status, doc = asc.call("GET", "/v1/builds", {
        "filter[app]": app_id, "filter[processingState]": "VALID", "filter[expired]": "false",
        "filter[preReleaseVersion.platform]": "IOS", "sort": "-uploadedDate", "limit": "25",
        "include": "preReleaseVersion,buildBetaDetail",
        "fields[builds]": "version,uploadedDate,processingState,expired,minOsVersion,preReleaseVersion,buildBetaDetail",
        "fields[preReleaseVersions]": "version,platform",
        "fields[buildBetaDetails]": "autoNotifyEnabled,internalBuildState,externalBuildState"})
    if status != 200:
        return apple_error(status, doc, "builds")
    included = {(i["type"], i["id"]): i for i in doc.get("included", [])}
    builds = []
    for b in doc.get("data", []):
        pre = included.get(("preReleaseVersions", rel_id(b, "preReleaseVersion") or ""))
        det = included.get(("buildBetaDetails", rel_id(b, "buildBetaDetail") or ""))
        builds.append({"id": b["id"], "build": attrs(b).get("version", ""), "version": attrs(pre).get("version", ""),
                       "uploaded": attrs(b).get("uploadedDate", ""), "processing": attrs(b).get("processingState", ""),
                       "internalState": attrs(det).get("internalBuildState", ""),
                       "externalState": attrs(det).get("externalBuildState", "")})
    status, doc = asc.call("GET", "/v1/apps/%s/betaGroups" % app_id, {
        "limit": "200", "fields[betaGroups]": "name,isInternalGroup,hasAccessToAllBuilds,publicLinkEnabled,createdDate"})
    if status != 200:
        return apple_error(status, doc, "groups")
    groups = [{"id": g["id"], "name": attrs(g).get("name", ""), "internal": bool(attrs(g).get("isInternalGroup")),
               "publicLink": bool(attrs(g).get("publicLinkEnabled")),
               "allBuilds": bool(attrs(g).get("hasAccessToAllBuilds"))} for g in doc.get("data", [])]
    return {"ok": True, "data": {"builds": builds, "groups": groups}}


def op_build_status(asc, a):
    build_id = safe_id(a.get("buildId"))
    out = {"externalState": "", "internalState": "", "reviewState": ""}
    status, doc = asc.call("GET", "/v1/builds/%s/buildBetaDetail" % build_id)
    if status != 200:
        return apple_error(status, doc, "detail")
    out["externalState"] = attrs(doc.get("data")).get("externalBuildState", "")
    out["internalState"] = attrs(doc.get("data")).get("internalBuildState", "")
    status, doc = asc.call("GET", "/v1/builds/%s/betaAppReviewSubmission" % build_id)
    if status == 200 and doc and doc.get("data"):
        out["reviewState"] = attrs(doc["data"]).get("betaReviewState", "")
    elif status not in (200, 404):
        return apple_error(status, doc, "review")
    return {"ok": True, "data": out}


def op_group_testers(asc, a):
    group_id = safe_id(a.get("groupId"))
    status, doc, items = paged(asc, "/v1/betaGroups/%s/betaTesters" % group_id, {
        "limit": "200", "fields[betaTesters]": "firstName,lastName,email,inviteType,state"})
    if status != 200:
        return apple_error(status, doc)
    return {"ok": True, "data": {"testers": [tester_view(t) for t in items]}}


def op_create_group(asc, a):
    app_id = safe_id(a.get("appId"))
    name = (a.get("name") or "").strip()
    if not name or len(name) > 100 or re.search(r"[\x00-\x1f]", name):
        raise Stop("bad_request")
    status, doc = asc.call("POST", "/v1/betaGroups", None, {"data": {
        "type": "betaGroups",
        "attributes": {"name": name, "isInternalGroup": False, "publicLinkEnabled": False, "feedbackEnabled": True},
        "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})
    if status not in (200, 201):
        return apple_error(status, doc)
    g = doc["data"]
    return {"ok": True, "data": {"group": {"id": g["id"], "name": attrs(g).get("name", name), "internal": False,
                                           "publicLink": False, "allBuilds": False}}}


def op_invite(asc, a):
    app_id, group_id, build_id = safe_id(a.get("appId")), safe_id(a.get("groupId")), safe_id(a.get("buildId"))
    raw = a.get("emails")
    if not isinstance(raw, list) or not raw or len(raw) > MAX_EMAILS:
        raise Stop("bad_request")
    emails, seen = [], set()
    for e in raw:
        e = str(e).strip()
        if e.lower() not in seen:
            seen.add(e.lower())
            emails.append(e)

    status, doc = asc.call("GET", "/v1/betaGroups/%s" % group_id)
    if status != 200:
        return apple_error(status, doc, "group")
    if attrs(doc.get("data")).get("isInternalGroup"):
        return {"ok": False, "error": {"status": 0, "stage": "group", "errors": [], "hint":
                "This is an internal group. Invitations here are for external groups only."}}
    status, doc = asc.call("GET", "/v1/builds/%s" % build_id)
    if status != 200:
        return apple_error(status, doc, "build")
    if attrs(doc.get("data")).get("processingState") != "VALID" or attrs(doc.get("data")).get("expired"):
        return {"ok": False, "error": {"status": 0, "stage": "build", "errors": [], "hint":
                "This build is not processed or has expired. Pick a processed build."}}

    status, doc = asc.call("POST", "/v1/betaGroups/%s/relationships/builds" % group_id, None,
                           {"data": [{"type": "builds", "id": build_id}]})
    build_note = "assigned"
    if status not in (200, 201, 204):
        detail = json.dumps(doc or {}).lower()
        if status == 409 and "already" in detail:
            build_note = "already_in_group"
        else:
            return apple_error(status, doc, "assign_build")

    status, doc, existing = paged(asc, "/v1/betaGroups/%s/betaTesters" % group_id,
                                  {"limit": "200", "fields[betaTesters]": "email"})
    if status != 200:
        return apple_error(status, doc, "list_testers")
    in_group = {attrs(t).get("email", "").lower() for t in existing}

    results = []
    for email in emails:
        row = {"email": email, "outcome": "", "detail": "", "testerId": ""}
        results.append(row)
        if not EMAIL_RE.match(email):
            row["outcome"] = "INVALID"
            continue
        if email.lower() in in_group:
            row["outcome"] = "ALREADY_IN_GROUP"
            continue
        tester_id = None
        status, doc = asc.call("POST", "/v1/betaTesters", None, {"data": {
            "type": "betaTesters", "attributes": {"email": email},
            "relationships": {"betaGroups": {"data": [{"type": "betaGroups", "id": group_id}]}}}})
        if status in (200, 201):
            tester_id, row["outcome"] = doc["data"]["id"], "ADDED_NEW"
        elif status == 409:
            status, doc = asc.call("GET", "/v1/betaTesters", {"filter[email]": email, "limit": "1"})
            found = (doc or {}).get("data") if status == 200 else None
            if found:
                tester_id = found[0]["id"]
                status, doc = asc.call("POST", "/v1/betaGroups/%s/relationships/betaTesters" % group_id, None,
                                       {"data": [{"type": "betaTesters", "id": tester_id}]})
                if status in (200, 201, 204):
                    row["outcome"] = "ADDED_EXISTING"
                else:
                    row["outcome"], row["detail"] = "ERROR", first_detail(doc, status)
                    tester_id = None
            else:
                row["outcome"], row["detail"] = "ERROR", first_detail(doc, status)
        else:
            row["outcome"], row["detail"] = "ERROR", first_detail(doc, status)
        if tester_id:
            row["testerId"] = tester_id
            status, doc = asc.call("POST", "/v1/betaTesterInvitations", None, {"data": {
                "type": "betaTesterInvitations", "relationships": {
                    "app": {"data": {"type": "apps", "id": app_id}},
                    "betaTester": {"data": {"type": "betaTesters", "id": tester_id}}}}})
            if status in (200, 201):
                row["invitation"] = "SENT"
            else:
                row["invitation"] = "NOT_SENT"
                row["detail"] = first_detail(doc, status)
    return {"ok": True, "data": {"build": build_note, "results": results}}


def first_detail(doc, status):
    errs = (doc or {}).get("errors") or []
    if errs:
        e = errs[0]
        return ("%s %s" % (e.get("title", ""), e.get("detail", ""))).strip()[:400]
    return "HTTP %s" % status


def op_remove(asc, a):
    group_id = safe_id(a.get("groupId"))
    ids = a.get("testerIds")
    if not isinstance(ids, list) or not ids or len(ids) > MAX_EMAILS:
        raise Stop("bad_request")
    ids = [safe_id(i) for i in ids]
    status, doc = asc.call("DELETE", "/v1/betaGroups/%s/relationships/betaTesters" % group_id, None,
                           {"data": [{"type": "betaTesters", "id": i} for i in ids]})
    if status not in (200, 204):
        return apple_error(status, doc)
    return {"ok": True, "data": {"removed": ids}}


def op_submit_beta_review(asc, a):
    """TestFlight beta app review only. Never the public App Store review."""
    build_id = safe_id(a.get("buildId"))
    status, doc = asc.call("POST", "/v1/betaAppReviewSubmissions", None, {"data": {
        "type": "betaAppReviewSubmissions",
        "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}})
    if status not in (200, 201):
        return apple_error(status, doc)
    return {"ok": True, "data": {"reviewState": attrs(doc.get("data")).get("betaReviewState", "")}}


OPS = {"ping": op_ping, "apps": op_apps, "overview": op_overview, "build_status": op_build_status,
       "group_testers": op_group_testers, "create_group": op_create_group, "invite": op_invite,
       "remove_testers": op_remove, "submit_beta_review": op_submit_beta_review}


# ---------------------------------------------------------------- main

def emit_error(code, **safe):
    extra = "".join(" %s=%s" % (k, v) for k, v in safe.items())
    print("TFMERR1 " + code + extra, flush=True)


def run(env):
    pem = env.get("TESTFLIGHT_PAYLOAD_PRIVATE_KEY", "")
    if not pem.strip():
        raise Stop("secret_missing")
    try:
        private_key = serialization.load_pem_private_key(pem.encode("utf-8"), password=None)
    except Exception:
        raise Stop("secret_invalid")
    mine = public_key_id(private_key.public_key())
    claimed = env.get("KEY_ID", "")
    if claimed != mine:
        raise Stop_with("key_mismatch", app_key=claimed, secret_key=mine)

    request = open_request(env.get("ENVELOPE", ""), private_key)
    rid = str(request.get("rid", ""))
    if not re.match(r"^[A-Za-z0-9_-]{8,64}$", rid):
        raise Stop("bad_request")
    if abs(time.time() - float(request.get("ts", 0))) > MAX_AGE_SECONDS:
        raise Stop("request_expired")
    try:
        response_key = b64d(request["rpk"])
        loaded = serialization.load_der_public_key(response_key)
        if loaded.key_size < 2048:
            raise Stop("bad_request")
    except Stop:
        raise
    except Exception:
        raise Stop("bad_request")
    op = request.get("op")
    if op not in OPS:
        raise Stop("bad_request")

    try:
        asc = Asc(env.get("APP_STORE_CONNECT_API_KEY_ID", ""), env.get("APP_STORE_CONNECT_ISSUER_ID", ""),
                  env.get("APP_STORE_CONNECT_API_PRIVATE_KEY", ""),
                  (env.get("ASC_API_BASE_FOR_TESTS") or ASC_BASE) if "GITHUB_ACTIONS" not in env else ASC_BASE)
    except Exception:
        raise Stop("apple_key_invalid")
    try:
        result = OPS[op](asc, request.get("args") or {})
    except Stop:
        raise
    except Exception:
        result = {"ok": False, "error": {"status": 0, "errors": [], "hint": "The request could not be completed."}}
    result["op"] = op
    sealed = seal_response(result, response_key, rid)
    parts = [sealed[i:i + CHUNK] for i in range(0, len(sealed), CHUNK)]
    for n, part in enumerate(parts, 1):
        print("TFMRES1 %s %d/%d %s" % (rid, n, len(parts), part), flush=True)


class Stop_with(Stop):
    def __init__(self, code, **safe):
        super().__init__(code)
        self.safe = safe


def main():
    try:
        run(os.environ)
        return 0
    except Stop_with as e:
        emit_error(str(e), **e.safe)
    except Stop as e:
        emit_error(str(e))
    except Exception:
        emit_error("internal")
    return 1


if __name__ == "__main__":
    sys.excepthook = lambda *a: None
    sys.exit(main())
