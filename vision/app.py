"""PresenceIQ edge vision service.

Images are processed in memory and never persisted. RTSP sources are configured
by alias through environment variables, never accepted as arbitrary user URLs.
Temporary ByteTrack IDs are scoped to a single monitoring session.
"""
import base64
import hmac
import json
import os
import threading
import time
from collections import OrderedDict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

api = FastAPI(title="PresenceIQ Edge Vision", version="0.1.0", docs_url=None, redoc_url=None)
TOKEN = os.environ.get("VISION_SHARED_TOKEN", "")
MODEL_PATH = os.environ.get("YOLO_MODEL_PATH", "/models/best.pt")
DEVICE = os.environ.get("VISION_DEVICE", "cpu")
BACKEND_URL = os.environ.get("PRESENCEIQ_INGEST_URL", "http://api:8080").rstrip("/")
INGEST_USER = os.environ.get("PRESENCEIQ_INGEST_USERNAME", "vision-ingest")
INGEST_PASS = os.environ.get("PRESENCEIQ_INGEST_PASSWORD", "")
CAMERA_STREAMS = json.loads(os.environ.get("CAMERA_STREAMS_JSON", "{}"))
if not isinstance(CAMERA_STREAMS, dict):
    raise RuntimeError("CAMERA_STREAMS_JSON must be an object keyed by camera aliases.")

# The service runs as one worker so in-memory per-session ByteTrack state remains coherent.
lock = threading.RLock()
session_models: "OrderedDict[str,tuple[Any,float]]" = OrderedDict()
MAX_TRACKER_SESSIONS = 4
MAX_IMAGE_BYTES = 2_500_000
stream_lock = threading.RLock()
streams: dict[str, dict[str, Any]] = {}


def authorize(token: str | None) -> None:
    if len(TOKEN) < 20 or not token or not hmac.compare_digest(token, TOKEN):
        raise HTTPException(status_code=401, detail="Unauthorized vision request")


def get_model(session_id: str):
    """A separate YOLO tracker instance per session; never share tracker identity state."""
    from ultralytics import YOLO
    if not Path(MODEL_PATH).is_file():
        raise HTTPException(status_code=503, detail="YOLO model weights not configured on this edge host")
    if session_id in session_models:
        model, _ = session_models.pop(session_id)
    else:
        model = YOLO(MODEL_PATH)
        names = model.names
        cls0 = names.get(0, "") if isinstance(names, dict) else names[0]
        if str(cls0).lower() != "person":
            raise HTTPException(status_code=503, detail="Class 0 of the configured model must be person")
    session_models[session_id] = (model, time.monotonic())
    while len(session_models) > MAX_TRACKER_SESSIONS:
        session_models.popitem(last=False)
    return model


def infer_bgr(session_id: str, frame: np.ndarray) -> dict[str, Any]:
    if frame.ndim != 3 or frame.shape[2] != 3:
        raise HTTPException(status_code=400, detail="Color image required")
    if frame.shape[0] * frame.shape[1] > 3840 * 2160:
        raise HTTPException(status_code=413, detail="Frame exceeds 4K pixel limit")
    with lock:
        model = get_model(session_id)
        try:
            results = model.track(frame, persist=True, tracker="bytetrack.yaml",
                                  imgsz=640, conf=0.10, iou=0.70,
                                  classes=[0], max_det=300,
                                  device=DEVICE, verbose=False)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="Inference failed; check edge logs") from exc
        boxes = results[0].boxes
        detections = []
        if boxes is not None:
            xyxy = boxes.xyxy.detach().cpu().numpy().tolist()
            confidences = boxes.conf.detach().cpu().numpy().tolist()
            track_ids = boxes.id.detach().cpu().numpy().astype(int).tolist() if boxes.id is not None else [None] * len(xyxy)
            for i, rect in enumerate(xyxy):
                detections.append({
                    "trackId": track_ids[i],
                    "confidence": round(float(confidences[i]), 5),
                    "box": [round(float(c), 2) for c in rect],
                })
    return {
        "count": len(detections),
        "trackedCount": sum(d["trackId"] is not None for d in detections),
        "meanConfidence": round(sum(d["confidence"] for d in detections) / len(detections), 5) if detections else 0.0,
        "detections": detections,
        "temporaryTrackingIds": True,
    }


@api.get("/health")
def health():
    return {"status": "ok", "modelConfigured": Path(MODEL_PATH).is_file(),
            "configuredCameras": len(CAMERA_STREAMS), "activeStreams": len(streams)}


@api.post("/detect")
async def detect(file: UploadFile = File(...), session_id: str = Form(...),
                 x_vision_token: str | None = Header(default=None, alias="X-Vision-Token")):
    authorize(x_vision_token)
    if len(session_id) > 100 or not session_id:
        raise HTTPException(status_code=400, detail="Invalid session_id")
    image = await file.read(MAX_IMAGE_BYTES + 1)
    if len(image) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Frame too large")
    if file.content_type not in ("image/jpeg", "image/png"):
        raise HTTPException(status_code=415, detail="Unsupported image type")
    frame = cv2.imdecode(np.frombuffer(image, dtype=np.uint8), cv2.IMREAD_COLOR)
    if frame is None:
        raise HTTPException(status_code=400, detail="Invalid image")
    return infer_bgr(session_id, frame)


class StreamCommand(BaseModel):
    session_id: str = Field(min_length=1, max_length=100)


def post_observation(session_id: str, evidence: dict[str, Any]):
    if not INGEST_PASS:
        raise RuntimeError("Vision ingest credentials not configured")
    payload = json.dumps({
        "count": evidence["count"],
        "trackedCount": evidence["trackedCount"],
        "meanConfidence": evidence["meanConfidence"],
    }).encode()
    basic = base64.b64encode((INGEST_USER + ":" + INGEST_PASS).encode()).decode()
    req = Request(BACKEND_URL + "/api/internal/sessions/" + session_id + "/observations",
                  data=payload, headers={"Authorization": "Basic " + basic,
                  "Content-Type": "application/json"}, method="POST")
    with urlopen(req, timeout=8) as response:
        if response.status != 200:
            raise RuntimeError(f"Backend rejected observation: HTTP {response.status}")


def stream_worker(camera_id: str, session_id: str, stop_event: threading.Event):
    """Ingest a trusted, preconfigured RTSP source at most every 3 seconds."""
    source = CAMERA_STREAMS[camera_id]
    video = cv2.VideoCapture(source)
    last_sent = 0.0
    failures = 0
    try:
        while not stop_event.is_set():
            ok, frame = video.read()
            if not ok:
                failures += 1
                if failures > 25:
                    break
                stop_event.wait(0.5)
                continue
            failures = 0
            now = time.monotonic()
            if now - last_sent < 3.0:
                continue
            last_sent = now
            try:
                event = infer_bgr(session_id, frame)
                post_observation(session_id, event)
            except HTTPError as exc:
                if exc.code in (401, 403, 404, 409):
                    # The backend no longer accepts this session; terminate the stream.
                    print(f"[RTSP {camera_id}] stopping: backend rejected session ({exc.code})", flush=True)
                    break
                print(f"[RTSP {camera_id}] backend temporary error: {exc.code}", flush=True)
            except Exception as exc:
                # No raw image or credential is written to logs.
                print(f"[RTSP {camera_id}] observation failed: {type(exc).__name__}", flush=True)
    finally:
        video.release()
        with stream_lock:
            current = streams.get(camera_id)
            if current and current["session_id"] == session_id:
                streams.pop(camera_id, None)


@api.post("/streams/{camera_id}/start")
def start_stream(camera_id: str, command: StreamCommand,
                 x_vision_token: str | None = Header(default=None, alias="X-Vision-Token")):
    authorize(x_vision_token)
    if camera_id not in CAMERA_STREAMS:
        raise HTTPException(status_code=404, detail="Camera alias not configured on edge host")
    if not Path(MODEL_PATH).is_file():
        raise HTTPException(status_code=503, detail="Model weights missing")
    with stream_lock:
        if camera_id in streams:
            raise HTTPException(status_code=409, detail="Camera stream already active")
        if len(streams) >= 2:
            raise HTTPException(status_code=429, detail="Maximum two concurrent RTSP streams")
        stop = threading.Event()
        thread = threading.Thread(target=stream_worker,
                                  args=(camera_id, command.session_id, stop),
                                  name="presenceiq-rtsp-" + camera_id, daemon=True)
        streams[camera_id] = {"session_id": command.session_id, "stop": stop, "thread": thread}
        thread.start()
    return {"camera": camera_id, "sessionId": command.session_id, "status": "STARTED"}


@api.post("/streams/{camera_id}/stop")
def stop_stream(camera_id: str, command: StreamCommand,
                x_vision_token: str | None = Header(default=None, alias="X-Vision-Token")):
    authorize(x_vision_token)
    with stream_lock:
        active = streams.get(camera_id)
        if not active or active["session_id"] != command.session_id:
            raise HTTPException(status_code=404, detail="No active matching session stream")
        active["stop"].set()
    return {"camera": camera_id, "status": "STOP_REQUESTED"}


@api.get("/streams")
def list_streams(x_vision_token: str | None = Header(default=None, alias="X-Vision-Token")):
    authorize(x_vision_token)
    with stream_lock:
        return [{"camera": key, "sessionId": v["session_id"]} for key, v in streams.items()]
