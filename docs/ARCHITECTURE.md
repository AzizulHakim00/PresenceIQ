# PresenceIQ Architecture and Trust Boundaries

## Services
- **React + Vite web:** browser UI on localhost:5173. Credentials remain in React memory and are not persisted in the browser.
- **Spring Boot (Java 21):** role-based REST API on localhost:8080, sessions, five temporal windows, human review, audit history, CSV, camera and identity management.
- **MongoDB:** private persistence behind the API. Collections: sessions, observations, reviews, audit_events, identities, checkins, cameras.
- **FastAPI + YOLO/ByteTrack edge service:** on the private Compose network. Raw camera frames are processed in memory; only numeric evidence and temporary track IDs are sent to Spring Boot.

## Identity and attendance
- A ByteTrack ID is a local tracker label, **not a person identity**.
- Recorded camera counts never create automatic named attendance.
- Identified roster check-ins are **claims** until manually verified by an authorized reviewer; no automatic link to a camera track is made.
- "ACCEPT_EVIDENCE" is not the same as accepting a person's attendance.
- Zero visible people is not proof of absence.
- Image retention and biometric matching are disabled by default.

## Five-window observation model
Window index = clamp(floor(elapsed_seconds * 5 / (plannedMinutes * 60)), 0, 4).
Each window shows number of samples, average observed occupancy, mean detection confidence, review signals, and one evidence state:
- NO_DATA: no observations.
- REVIEW_REQUIRED: confidence/gap/zero-count issue or too few samples.
- OBSERVED: at least three samples and no flagged signals.

A session-wide conclusion is not inferred from window states. Live window boundaries are anchored to session start and planned duration; this should be revisited for variable-length sessions.

## Security model
Admin, reviewer and edge ingestion accounts are read from environment variables; the service rejects short/missing secrets. Spring method-level authorization guards mutations. Vision endpoints require a separate shared token. Only camera aliases are sent to the edge service; RTSP URLs and credentials are provided through private edge environment configuration, not stored in MongoDB.
This is a development MVP using stateless HTTP Basic. **It must not be deployed to the public internet as is.** Before production: TLS, OIDC, session/CSRF controls, strong password hashing/secret management, multi-tenancy, rate limits, encryption and consent/retention policies.

## Cloudinary
Cloudinary is intentionally not used in this version. Uploading raw classroom or workplace camera frames to a third-party media host requires an explicit retention policy, restricted delivery settings, and authorized consent. No such uploads occur here.

## V11 research results
V11.3 full-video observations and V11.4 tracking evaluation are separate from this product. V11.4 W1 identity metrics remain provisional due to annotation issues. Do not describe this application as having validated named attendance accuracy.
