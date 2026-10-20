#!/usr/bin/env python3
"""Generate an ignored build resource; never print credential values."""
import argparse
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent

def configuration(mode, environ):
    if mode == "standard":
        return {"mode": mode}
    api_id = environ.get("TELEGRAM_API_ID", "").strip()
    api_hash = environ.get("TELEGRAM_API_HASH", "").strip()
    if not re.fullmatch(r"[1-9][0-9]{0,9}", api_id) or int(api_id) > 2147483647 or not re.fullmatch(r"[a-fA-F0-9]{32}", api_hash):
        raise ValueError("Configured builds require valid TELEGRAM_API_ID and TELEGRAM_API_HASH build secrets.")
    return {"mode": mode, "apiID": api_id, "apiHash": api_hash}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["standard", "configured"], default="configured")
    parser.add_argument("--platform", choices=["ios", "android", "both"], default="both")
    args = parser.parse_args()
    paths = {"ios": ROOT / "Resources/BuildConfig/telegram-build.json", "android": ROOT / "android/app/src/main/assets/telegram-build.json"}
    selected = list(paths.values()) if args.platform == "both" else [paths[args.platform]]
    # Remove stale configured output even when validation fails.
    for path in selected:
        path.unlink(missing_ok=True)
    try:
        data = configuration(args.mode, os.environ)
    except ValueError as error:
        parser.exit(1, str(error) + "\n")
    for path in selected:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data) + "\n")
        path.chmod(0o600)
    print("Telegram build configuration generated (" + args.mode + ").")

if __name__ == "__main__":
    main()
