# Running the PresenceIQ Application

## 1. Local prerequisites
Docker Engine + Docker Compose v2, and the licensed V9 YOLO person/face checkpoint (class 0 MUST be `person`).

## 2. Secrets and local environment
```bash
cp .env.example .env
```
Replace every `REPLACE_WITH...` value with a distinct random secret of at least 20 characters. **Never commit the .env file.**
Place your YOLO checkpoint in `models/best.pt` (ignored by Git).

```bash
docker compose up --build
```

Open http://localhost:5173 . API health: http://localhost:8080/api/health .
Log in using `PRESENCEIQ_ADMIN_USERNAME` and `PRESENCEIQ_ADMIN_PASSWORD`.
Reviewers have separate credentials and cannot create sessions or edit the roster.

### MongoDB Atlas
Use a dedicated app database and dedicated least-privileged database user. Replace `MONGO_URI` in .env with the Atlas URI. Allow only required outbound IPs and never paste DB credentials into GitHub. By default Docker Compose uses the isolated local MongoDB service.

## 3. First end-to-end test (no camera required)
1. Create a session (CLASSROOM, 5 minutes).
2. Start it.
3. Under the session, record a manual demonstration count of 20 (confidence 0.8).
4. Record a count of 0 to create a review event. This represents "no person observed", not absence.
5. Go to Review queue, add a note, accept/reject the *evidence* (not attendance).
6. Refresh: the decision should persist in MongoDB.
7. Export the session CSV; all five windows must appear.
8. Register a person under Identity Registry (only with authorization), create a manual/QR/NFC credential claim and verify it. This does not link the person to any tracking ID.
9. Open Audit History and verify the actions were recorded.

## 4. Live browser webcam (requires real weights)
Start an ACTIVE session. Under Cameras, click "Start browser camera" and grant browser permission. The browser sends one resized JPEG about every 4 seconds to the API, which proxies to the edge vision service. Only numeric evidence enters MongoDB; raw JPEGs are not saved.
For deployment, HTTPS is required for camera permissions. Ensure that no camera captures uninformed or unauthorized individuals.

## 5. RTSP source (restricted aliases)
Set `CAMERA_STREAMS_JSON` in the vision service environment (e.g. `{"lab-1":"rtsp://..."}`). Use aliases only; do not supply an arbitrary URL through the HTTP API. Register the exact RTSP alias as a Camera in the dashboard, start a session and press Start under Cameras. RTSP observation ingestion is configured on the private Compose network.

## 6. Preproduction checklist
- Replace HTTP Basic with OIDC and CSRF-protected sessions.
- TLS, secrets manager, scoped MongoDB role, audit/retention controls.
- Rate limiting, live stream disconnection/restart handling, structured logging.
- TrackEval benchmark beyond W1; approve annotation QA.
- Design real credential verification (signed QR or reader events). A UI-entered reference is **not** cryptographic verification.
- Performance/load testing on real camera devices.
- Reject arbitrary snapshots uploaded to public media/CDN services.
