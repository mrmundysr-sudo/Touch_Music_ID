"""Offline tests for testflight_tester_admin.py. Run: cd .github/scripts && python -m unittest test_testflight_tester_admin
No network, no secrets: keys are generated on the fly."""
import base64, io, json, os, sys, time, unittest, zlib, contextlib
sys.path.insert(0, os.path.dirname(__file__))
import testflight_tester_admin as w
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa, ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def seal_request(pub, payload):
    aes = AESGCM.generate_key(bit_length=256)
    nonce = os.urandom(12)
    ct = AESGCM(aes).encrypt(nonce, zlib.compress(json.dumps(payload).encode()), w.AAD_REQUEST)
    return json.dumps({"v": 1, "k": w.b64e(pub.encrypt(aes, w.OAEP)), "n": w.b64e(nonce), "c": w.b64e(ct)})


class Tests(unittest.TestCase):
    def setUp(self):
        self.wf = rsa.generate_private_key(65537, 2048)
        self.resp = rsa.generate_private_key(65537, 2048)
        self.pem = self.wf.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                         serialization.NoEncryption()).decode()
        spki = lambda k: k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.rpk = w.b64e(spki(self.resp))
        self.kid = w.public_key_id(self.wf.public_key())
        self.asc_pem = ec.generate_private_key(ec.SECP256R1()).private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()

    def env(self, payload, **over):
        e = {"ENVELOPE": seal_request(self.wf.public_key(), payload), "KEY_ID": self.kid,
             "TESTFLIGHT_PAYLOAD_PRIVATE_KEY": self.pem, "APP_STORE_CONNECT_API_PRIVATE_KEY": self.asc_pem,
             "APP_STORE_CONNECT_API_KEY_ID": "K", "APP_STORE_CONNECT_ISSUER_ID": "I"}
        e.update(over)
        return e

    def test_blocked_endpoints_never_reach_the_network(self):
        asc = w.Asc("K", "I", self.asc_pem, "http://127.0.0.1:1")
        for m, p in [("POST", "/v1/appStoreVersionSubmissions"), ("POST", "/v1/reviewSubmissions"),
                     ("POST", "/v1/builds"), ("DELETE", "/v1/betaTesters/x"), ("GET", "/v1/users")]:
            with self.assertRaises(w.Stop):
                asc.call(m, p)

    def test_tampered_or_wrong_key_is_rejected_with_a_fixed_code(self):
        env = self.env({"op": "ping"})
        doc = json.loads(env["ENVELOPE"])
        raw = bytearray(w.b64d(doc["c"]))
        raw[0] ^= 1
        doc["c"] = w.b64e(bytes(raw))
        env["ENVELOPE"] = json.dumps(doc)
        with self.assertRaises(w.Stop) as c:
            w.run(env)
        self.assertEqual(str(c.exception), "decrypt_failed")
        with self.assertRaises(w.Stop) as c:
            w.run(self.env({"op": "ping"}, KEY_ID="0" * 16))
        self.assertEqual(str(c.exception), "key_mismatch")

    def test_expired_request_is_rejected(self):
        payload = {"op": "ping", "rid": "abcdefgh12", "ts": time.time() - 3600, "rpk": self.rpk}
        with self.assertRaises(w.Stop) as c:
            w.run(self.env(payload))
        self.assertEqual(str(c.exception), "request_expired")

    def test_output_is_ciphertext_only(self):
        email = "someone.private@example.org"
        payload = {"op": "ping", "rid": "abcdefgh12", "ts": time.time(), "rpk": self.rpk,
                   "args": {"emails": [email]}}
        env = self.env(payload)
        orig = w.OPS["ping"]
        w.OPS["ping"] = lambda asc, a: {"ok": True, "data": {"echo": a["emails"]}}
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                w.run(env)
        finally:
            w.OPS["ping"] = orig
        text = out.getvalue()
        self.assertNotIn("example.org", text)
        self.assertNotIn(email, text)
        line = text.strip().splitlines()[0]
        _, rid, seq, chunk = line.split(" ")
        env_json = json.loads(base64.b64decode(chunk))
        aes = self.resp.decrypt(w.b64d(env_json["k"]), w.OAEP)
        plain = AESGCM(aes).decrypt(w.b64d(env_json["n"]), w.b64d(env_json["c"]), b"tfm1-res:abcdefgh12")
        self.assertEqual(json.loads(zlib.decompress(plain))["data"]["echo"], [email])


if __name__ == "__main__":
    unittest.main()
