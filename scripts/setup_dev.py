#!/usr/bin/env python3
"""Initialize a private .env file for PresenceIQ local Docker development.

Run: python scripts/setup_dev.py

It never uploads, prints or commits secrets, and refuses to replace an existing
.env file. Edit the resulting .env to configure MongoDB Atlas or RTSP, if needed.
"""
from pathlib import Path
import os
import re
import secrets

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / ".env.example"
DEST = ROOT / ".env"

def main():
    if DEST.exists():
        raise SystemExit("Existing .env detected. Refusing to replace credentials.")
    raw = TEMPLATE.read_text(encoding="utf-8")
    names = re.findall(r"REPLACE_WITH_[A-Z_]+", raw)
    if len(names) != 5:
        raise SystemExit(f"Unexpected secret template count ({len(names)}); inspect .env.example.")
    values = {name: secrets.token_urlsafe(36) for name in set(names)}
    for name, value in values.items():
        raw = raw.replace(name, value)
    if "REPLACE_WITH_" in raw:
        raise SystemExit("Unreplaced template secret found")
    fd = os.open(DEST, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as out:
        out.write(raw)
    print("Private local .env created; no secrets printed.")
    print("Edit .env to inspect admin/reviewer usernames and passwords.")
    print("Now copy your approved class-0-person YOLO checkpoint to models/best.pt.")
    print("Then run: docker compose up --build")

if __name__ == "__main__":
    main()
