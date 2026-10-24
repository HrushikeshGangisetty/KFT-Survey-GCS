"""Signing S9: synthetic keys and GCS bundles for the SITL run, made with the S6 provisioning code itself.

Run on Windows (needs argon2-cffi, cryptography, pymavlink, pyserial, as provision.py does):
    py -3.12 tools/sitl/s9_make_keys.py --provision ../GNSS-Denied/tools/signing/provision.py --out build/s9-sitl

Writes into --out:
    keys.json        drone uids, system ids and keys (hex), the wrong key, the vault PIN and bundle passphrase
    bundle_ab.vnkey  both signing drones (GCS 1)
    bundle_a.vnkey   drone A only (GCS 2: it holds no key for drone B, PRD row T7)

Every value here is a public, disposable SITL test value. The master is a fixed hash, so reruns give the same keys.
Keys come from provision.derive_key (HMAC-SHA256(master, "VNAV-SIGN-v1" || uid)) and bundles from provision.seal, so
the GCS imports exactly what the real tool would export. Nothing here talks to a drone.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--provision", type=Path, required=True)
ap.add_argument("--out", type=Path, required=True)
args = ap.parse_args()

spec = importlib.util.spec_from_file_location("provision", args.provision)
provision = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provision)

master = hashlib.sha256(b"S9 public disposable SITL master").digest()
# SITL instance 0 and 1 sign; instance 2 (system id 3) stays unsigned, like another KFT product (T8).
drones = [dict(name="A", sysid=1, uid="5e9a00000000000000000001"), dict(name="B", sysid=2, uid="5e9a00000000000000000002")]
for d in drones:
    d["key_hex"] = provision.derive_key(master, bytes.fromhex(d["uid"])).hex()

passphrase = "s9 public disposable bundle"


def entry(d):
    return dict(drone_id=d["uid"], key_b64=base64.b64encode(bytes.fromhex(d["key_hex"])).decode("ascii"), key_version=1,
                label=f"S9 SITL {d['name']}", added_at=provision.now_iso(), sysid_hint=None)


args.out.mkdir(parents=True, exist_ok=True)
(args.out / "bundle_ab.vnkey").write_bytes(provision.seal([entry(d) for d in drones], passphrase, provision.BUNDLE_MAGIC))
(args.out / "bundle_a.vnkey").write_bytes(provision.seal([entry(drones[0])], passphrase, provision.BUNDLE_MAGIC))
(args.out / "keys.json").write_text(json.dumps(dict(
    drones=drones, unsigned_sysid=3, wrong_key_hex=bytes(range(32)).hex(), pin="s9-sitl-pin", bundle_passphrase=passphrase,
), indent=2))
print(f"wrote keys.json and two bundles to {args.out}")
