#!/usr/bin/env python3
"""Import V11.3 sampled-frame evidence into the PresenceIQ session engine.

Reads saved *core-only* V11.3 frame and detection CSVs. Creates an ACTIVE
historical replay session and submits idempotent frame batches to Spring Boot.
No YOLO inference, video decoding, image uploads or named identity linkage.

Example:
  PRESENCEIQ_ADMIN_PASSWORD=... PRESENCEIQ_INGEST_PASSWORD=... \
  python scripts/import_v11_3.py --run /path/to/run_b0551157b9f1 \
      --checkpoint /persistent/drive/presenceiq_replay_checkpoint.json
"""
import argparse
import base64
import csv
import json
import math
import os
import sys
import time
from collections import defaultdict
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

FRAME_NAMES = ["v11_3_all_chunk_frame_summary.csv", "v11_3_all_frame_summary.csv"]
DETECTION_NAMES = ["v11_3_all_chunk_detections.csv", "v11_3_all_detections.csv"]
SUMMARY_NAME = "v11_3_full_video_tracking_summary.json"


def first_file(root, names):
    for name in names:
        found = root / name
        if found.is_file():
            return found
    raise FileNotFoundError(f"No file named {names} in {root}")


def api_request(base, path, username, password, method="GET", payload=None):
    if not username or not password:
        raise RuntimeError("API username and password are required.")
    auth = base64.b64encode(f"{username}:{password}".encode()).decode()
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Authorization": f"Basic {auth}", "Content-Type": "application/json"}
    req = Request(base.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urlopen(req, timeout=25) as response:
            raw = response.read()
            return json.loads(raw.decode()) if raw else {}
    except HTTPError as exc:
        raise RuntimeError(f"API HTTP {exc.code}: {exc.read(300).decode(errors='replace')}") from exc
    except URLError as exc:
        raise RuntimeError(f"API unreachable: {exc.reason}") from exc


def load_counts(path):
    sums = defaultdict(lambda: [0.0, 0])
    with path.open(newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if str(row.get("is_core", "1")).lower() not in ("1", "true", "1.0"):
                continue
            sf = int(row["source_frame"])
            totals = sums[sf]
            totals[0] += float(row["confidence"])
            totals[1] += 1
    return sums


def frames_to_import(frames_path, detections):
    seen = set()
    with frames_path.open(newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if str(row.get("is_core", "1")).lower() not in ("1", "true", "1.0"):
                continue
            sf = int(row["source_frame"])
            if sf in seen:
                raise ValueError(f"Duplicate core source frame: {sf}")
            seen.add(sf)
            count = int(row["detections"])
            tracked = int(row["tracked_detections"])
            confidence_sum, n = detections.get(sf, [0.0, 0])
            if n != count:
                raise ValueError(f"Core detection count mismatch at {sf}: frame {count}, detections CSV {n}")
            yield {
                "count": count, "trackedCount": tracked,
                "meanConfidence": round(confidence_sum / n, 6) if n else 0.0,
                "elapsedSeconds": int(float(row["timestamp_sec"])),
                "externalRef": f"v11_3_video001_frame_{sf:08d}",
            }


def checkpoint_save(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(str(path) + ".tmp")
    tmp.write_text(json.dumps(state, indent=2), encoding="utf-8")
    tmp.replace(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run", required=True, type=Path, help="V11.3 run folder with the combined CSV files")
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--checkpoint", type=Path, default=Path("presenceiq_replay_checkpoint.json"))
    ap.add_argument("--session-id", default="", help="Reuse an existing ACTIVE session, if desired")
    ap.add_argument("--batch-size", type=int, default=100)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    if not 1 <= args.batch_size <= 200:
        ap.error("--batch-size must be 1..200")
    root = args.run
    summary = json.loads((root / SUMMARY_NAME).read_text(encoding="utf-8"))
    if not summary.get("coverage", {}).get("all_chunks_complete"):
        raise RuntimeError("V11.3 run is incomplete; refusing to import partial full-video results.")
    frames_path = first_file(root, FRAME_NAMES)
    detection_path = first_file(root, DETECTION_NAMES)
    print("Reading counts from:", detection_path)
    counts = load_counts(detection_path)
    expected = summary["coverage"]["expected_core_sampled_frames"]
    if len(counts) != expected:
        print("Warning: number of frames with detections differs from expected", len(counts), expected)
    data = list(frames_to_import(frames_path, counts))
    if len(data) != expected:
        raise RuntimeError(f"Expected {expected} unique core frames, found {len(data)}.")
    print("Read", len(data), "core frames; first record:", data[0])
    if args.dry_run:
        print("DRY RUN ONLY: no session or database changed.")
        return
    state = json.loads(args.checkpoint.read_text()) if args.checkpoint.is_file() else {}
    admin = os.environ.get("PRESENCEIQ_ADMIN_USERNAME", "admin")
    admin_pass = os.environ.get("PRESENCEIQ_ADMIN_PASSWORD", "")
    ingest = os.environ.get("PRESENCEIQ_INGEST_USERNAME", "vision-ingest")
    ingest_pass = os.environ.get("PRESENCEIQ_INGEST_PASSWORD", "")
    sid = args.session_id or state.get("sessionId")
    if not sid:
        planned = math.ceil(summary["video"]["duration_seconds"] / 60)
        session = api_request(args.api, "/api/sessions", admin, admin_pass, "POST", {
            "title": "V11.3 source video 001 — historical replay",
            "environment": "CLASSROOM", "plannedMinutes": planned,
        })
        sid = session["_id"]
        state = {"sessionId": sid, "nextOffset": 0, "sourceRun": str(root.absolute())}
        checkpoint_save(args.checkpoint, state)
        api_request(args.api, f"/api/sessions/{sid}/start", admin, admin_pass, "POST")
    elif state.get("sourceRun") and state["sourceRun"] != str(root.absolute()):
        raise RuntimeError("Checkpoint belongs to a different V11.3 run.")
    start = int(state.get("nextOffset", 0))
    print("Session:", sid, "| resuming at row", start)
    for offset in range(start, len(data), args.batch_size):
        batch = data[offset:offset + args.batch_size]
        result = api_request(args.api,
            f"/api/internal/sessions/{sid}/replay/batch", ingest, ingest_pass,
            "POST", batch)
        if result.get("processed") != len(batch):
            raise RuntimeError(f"Unexpected batch response at offset {offset}: {result}")
        state = {"sessionId": sid, "nextOffset": offset + len(batch),
                 "sourceRun": str(root.absolute()), "completed": False}
        checkpoint_save(args.checkpoint, state)
        print(f"Imported {state['nextOffset']:,} / {len(data):,} observations")
    state["completed"] = True
    checkpoint_save(args.checkpoint, state)
    print("COMPLETE. Open session", sid, "in PresenceIQ.")
    print("Data are historical replay, not real-time attendance.")


if __name__ == "__main__":
    main()
