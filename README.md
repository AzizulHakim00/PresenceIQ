# PresenceIQ

**An uncertainty-aware presence observation platform for classrooms, offices and industrial workplaces.**

PresenceIQ combines person detection, temporal tracking evidence, five observation windows, human review, and auditable session reporting. Named student/employee identity is recorded only through separately authorized credential claims; **a ByteTrack tracking ID is not a person's identity**, and camera data never automatically marks someone present or absent.

## Platform architecture

| Component | Technology | Responsibility |
| --- | --- | --- |
| Web console | React 19 / Vite | Dashboards, sessions, reviews, identity and camera management |
| Main API | Java 21 / Spring Boot 3.5 | Role-based sessions, evidence, audit, CSV, identity and cameras |
| Database | MongoDB | Sessions, observations, reviews, audits, people, claims and camera aliases |
| Edge vision | Python / FastAPI / YOLO11n / ByteTrack | Person-only inference, browser webcam and preconfigured RTSP streams |
| Local deployment | Docker Compose | Four-service development stack |

## Implemented modules

- **Session Manager:** create/start/close sessions and persist their status.
- **Five-window Temporal Engine:** occupancy counts, confidence, observation gaps and evidence states.
- **Review Workbench:** human review of uncertain observations with notes and decisions.
- **Audit History:** reviewer/session/identity/camera actions stored with actor and timestamp.
- **Reporting:** downloadable CSV with all five observation windows, including no-data windows.
- **Identity Registry:** authorized student/employee records, QR/NFC/manual *claims* with separate reviewer verification; no automatic linkage to camera tracks.
- **Camera Registry and live capture:** local browser webcam under explicit browser permission; RTSP feeds only through private environment-configured camera aliases.
- **Basic security boundaries:** local HTTP Basic admin/reviewer/ingestion roles and separate token-gated vision service; no public raw video archives or cloud media uploads.

## Quick start

```bash
git clone https://github.com/AzizulHakim00/PresenceIQ.git
cd PresenceIQ
python scripts/setup_dev.py
# Setup generates private, distinct secrets. Inspect .env locally.
# On Windows, instead run:
# powershell -ExecutionPolicy Bypass -File .\\scripts\\start-local.ps1
# Put the existing trained class-0-person YOLO checkpoint at models/best.pt.
docker compose up -d --build\n# For live camera mode AFTER putting your checkpoint into models/best.pt:\n# docker compose --profile vision up -d --build
```

Open **http://localhost:5173** and log in using the admin account configured in `.env`. Backend health: **http://localhost:8080/api/health**.

Read **[Development guide](docs/DEVELOPMENT.md)** and **[Architecture & trust boundaries](docs/ARCHITECTURE.md)** before testing live streams.

## Research and product are separate

Prior V11.3 sampled-video data: 10,921 sampled core frames and 275,300 person detections, with 971 chunk-local tracklets. V11.4 W1 tracking metrics remain **provisional** while the ground-truth QA is corrected. This application doesn't automatically claim that its tracking IDs reflect persistent real-world identities.

## Security and release status

**Development MVP only. Not approved for public production deployment.** Before public access, replace HTTP Basic with proper organization/user identity (OIDC), add TLS, CSRF/session controls, rate limiting, tenant isolation, consent/data retention governance, secure camera configuration, and validated credential verification. Use a dedicated database/user for the app. No Cloudinary uploads are implemented because storing identifiable frames requires privacy controls and explicit authorization.

## Roadmap

- Add organization-based multi-tenancy and migration-safe database schema.
- Validate tracking reliability on more than the six-second W1 sample.
- Replace manual credential claim reference with cryptographically verifiable QR/NFC events.
- Harden live camera ingestion and add secure scoped deployments.
- Add automated integration/e2e tests for the Spring Boot + React + MongoDB system.

MIT/license and production compliance terms are **not yet selected**.

## Startup troubleshooting

The default Compose command starts the **MongoDB + API + dashboard** services only, so you can log in without downloading the PyTorch/YOLO image. For camera inference, use the optional `vision` profile and your trained `models/best.pt` checkpoint.

On Windows PowerShell run:

```powershell
powershell -ExecutionPolicy Bypass -File .\\scripts\\start-local.ps1
```

If Docker Desktop or the Engine is not running, the helper prints a clear error. To inspect the containers without revealing private credentials:

```powershell
docker compose ps --all
docker compose logs --tail=70 api web mongodb
```

Do **not** share `.env`, `docker compose config` output, RTSP URLs or any secret-containing logs. Review and redact sensitive details first.
