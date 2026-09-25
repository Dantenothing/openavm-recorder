"""Check a Phone APK without ever printing configuration values.

Use --reference-apk with a privately retained earlier APK for an additional exact-value
comparison. This is an artifact guard, not a claim that every possible secret is absent.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

FIELDS = (
    "hmac_access_key", "hmac_secret_key", "password_public_key",
    "prod_secret", "vin_key", "vin_iv",
)
LEGACY_ASSET = "assets/connection/zeekr-au-166.json"
PRIVATE_KEY_BLOCK = re.compile(rb"-----BEGIN (?:RSA |EC )?PRIVATE KEY-----[\r\n ]+[A-Za-z0-9+/=\r\n ]{32,}-----END (?:RSA |EC )?PRIVATE KEY-----")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--reference-apk", type=Path)
    args = parser.parse_args()
    needles = {}
    if args.reference_apk:
        with zipfile.ZipFile(args.reference_apk) as reference:
            config = json.loads(reference.read(LEGACY_ASSET))
        if not all(isinstance(config.get(k), str) and config[k].strip() for k in FIELDS):
            raise SystemExit("Reference must contain all six non-empty fields; values were not printed.")
        for field in FIELDS:
            value = config[field]
            needles[field] = {value.encode("utf-8"), value.encode("utf-16-le"),
                              json.dumps(value, ensure_ascii=True)[1:-1].encode("ascii")}
    findings = []
    scanned = 0
    with zipfile.ZipFile(args.apk) as apk:
        for item in apk.infolist():
            if item.is_dir():
                continue
            data = apk.read(item)
            scanned += 1
            if item.filename == LEGACY_ASSET:
                findings.append({"entry": item.filename, "reason": "legacy configuration asset"})
            if item.filename.lower().endswith((".jks", ".keystore", ".p12", ".pfx")):
                findings.append({"entry": item.filename, "reason": "unexpected key-store file"})
            # PEM parser libraries contain bare BEGIN/END strings; those alone are not keys.
            if PRIVATE_KEY_BLOCK.search(data):
                findings.append({"entry": item.filename, "reason": "private key PEM block"})
            if item.filename.lower().endswith(".json"):
                try:
                    candidate = json.loads(data)
                except (ValueError, UnicodeError):
                    candidate = None
                if isinstance(candidate, dict) and any(isinstance(candidate.get(k), str) and candidate[k].strip() for k in FIELDS):
                    findings.append({"entry": item.filename, "reason": "non-empty protocol configuration"})
            for field, variants in needles.items():
                if any(value in data for value in variants):
                    findings.append({"entry": item.filename, "reference_field": field})
    report = {"artifact": args.apk.name, "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "bytes": args.apk.stat().st_size, "entries_scanned": scanned,
              "reference_fields_compared": len(needles), "findings": findings,
              "result": "FAIL" if findings else "PASS",
              "scope": "Uncompressed entries, legacy asset, non-empty JSON fields, key-file markers and optional exact reference values; no server or legal assessment."}
    print(json.dumps(report, indent=2))
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
