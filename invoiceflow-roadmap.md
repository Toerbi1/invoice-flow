# InvoiceFlow — Roadmap

Vorgehen: strikt inkrementell, jede Phase endet mit einem Validierungs-Gate. Erst wenn das Gate grün ist, beginnt die nächste Phase. Jede Phase entspricht einem eigenen Branch + PR (Conventional Commits, Issue pro Phase).

---

## Übersicht

| Phase | Titel | Kern-Deliverable | Aufwand (grob) |
|---|---|---|---|
| 0 | Fundament | Compose-Infra läuft, Topics existieren | 0,5–1 Tag |
| 1 | Upload & Domäne | PDF-Upload → Postgres + MinIO + Kafka-Event | 1–2 Tage |
| 2 | Extraction-Pipeline | Gateway + Worker (ZMQ), Ergebnis in Mongo | 2–3 Tage |
| 3 | Verarbeitung & Validierung | Statusmaschine, Dubletten-Check | 1–2 Tage |
| 4 | Frontend-Durchstich | E2E-Flow ohne curl | 2–3 Tage |
| 5 | Freigabe & Audit | Approve/Reject, Audit-Trail | 1–2 Tage |
| 6 | Security | Self-issued JWT, Rollen, Method Security | 1–2 Tage |
| 7 | Härtung & Abschluss | Tests, CI, Doku, Demo-Szenario | 1–2 Tage |

Meilensteine:
- **M1 „Pipeline lebt"** = Ende Phase 2: Upload löst automatisch Extraktion aus
- **M2 „Produkt sichtbar"** = Ende Phase 4: kompletter Flow im Browser
- **M3 „Enterprise-ready"** = Ende Phase 6: Auth + Audit vollständig

---

## Phase 0 — Fundament

**Ziele**
- Monorepo `invoiceflow` anlegen (Branch Protection, Issue-Templates, Conventional Commits)
- `docker-compose.yml` mit: Postgres 16, MongoDB 7, Kafka (KRaft, single node), MinIO, Kafka-UI
- Healthchecks auf allen Infra-Diensten
- Init-Container: Kafka-Topics anlegen (`invoice.received`, `invoice.extracted`, `invoice.validated`, `invoice.approved`, `invoice.rejected`), MinIO-Bucket `invoices` erstellen
- `.env.example` mit allen Credentials/Ports

**Gate ✅**
- `docker compose up -d` → alle Container `healthy`
- Test-Message per `kafka-console-producer` in Kafka-UI sichtbar
- MinIO-Console erreichbar, Bucket `invoices` existiert

---

## Phase 1 — Upload & Domäne (invoice-service)

**Ziele**
- Spring Boot 3.x Projekt: Web, Data JPA, Flyway, Kafka, MinIO-Client (S3 SDK)
- Flyway V1: Tabellen `invoice`, `supplier`
- `POST /api/invoices` (Multipart): PDF → MinIO, Datensatz mit Status `RECEIVED`, Event `invoice.received` publizieren
- `GET /api/invoices`, `GET /api/invoices/{id}`
- Test-PDF-Generator (`tools/invoice-generator`, ReportLab, 2–3 Layout-Varianten)

**Gate ✅**
- `curl`-Upload eines generierten PDFs → Zeile in Postgres, Objekt in MinIO, Event in Kafka-UI
- Integrationstest mit Testcontainers (Postgres + Kafka) grün

---

## Phase 2 — Extraction-Pipeline (Python + ZMQ)

**Ziele**
- `extraction-gateway`: Kafka-Consumer (`invoice.received`), PDF aus MinIO laden, Job per ZMQ PUSH verteilen, Ergebnisse per PULL einsammeln, Timeout/Retry (60 s, max. 3 Versuche)
- `extraction-worker`: ZMQ PULL, pdfplumber-Textextraktion, Regex-/Keyword-Regeln für Lieferant, Rechnungsnummer, Beträge, Datum; heuristische Konfidenz-Scores
- Ergebnis nach Mongo (`extractions`), Event `invoice.extracted` publizieren
- Unit-Tests der Extraktions-Regeln gegen die generierten Test-PDFs

**Gate ✅**
- Upload aus Phase 1 löst Extraktion automatisch aus (kein manueller Schritt)
- Mongo-Dokument mit Feldern + Konfidenzen vorhanden, Event in Kafka-UI
- `docker compose up --scale extraction-worker=3` → Jobs nachweislich auf Worker verteilt (Logs)
- Fehlerfall getestet: korruptes PDF → `EXTRACTION_FAILED` wird gemeldet

---

## Phase 3 — Verarbeitung & Validierung

**Ziele**
- invoice-service konsumiert `invoice.extracted`, übernimmt Felder in die Entity
- Statusmaschine: RECEIVED → EXTRACTING → EXTRACTED → PENDING_APPROVAL / VALIDATION_FAILED; Übergänge nur über Domain-Methoden
- Validierung: Pflichtfelder, Konfidenz-Schwellwert, Dubletten-Check (Unique Constraint `supplier + invoiceNumber`)
- `PATCH /api/invoices/{id}` für manuelle Feld-Korrekturen bei VALIDATION_FAILED
- Event `invoice.validated`

**Gate ✅**
- Rechnung durchläuft RECEIVED → PENDING_APPROVAL automatisch, sichtbar über API
- Dublette (gleicher Lieferant + Rechnungsnummer) wird abgewiesen
- Unzulässiger Statusübergang wird mit Fehler quittiert (Test)

---

## Phase 4 — Frontend-Durchstich (React)

**Ziele**
- Vite + React 18 + TypeScript, React Query, React Router
- Upload-Seite (Drag & Drop), Rechnungsliste mit Status-Badges + Filter
- Detailansicht: PDF-Vorschau (MinIO Presigned URL), Extraktionsfelder mit Konfidenz-Markierung (< Schwellwert = hervorgehoben), Korrektur-Formular
- Polling oder SSE für Status-Updates nach Upload

**Gate ✅**
- Kompletter E2E-Flow im Browser: Upload → Extraktion → Korrektur → PENDING_APPROVAL, ohne curl
- Niedrig-Konfidenz-Felder sind visuell markiert

---

## Phase 5 — Freigabe-Workflow & Audit

**Ziele**
- `POST /api/invoices/{id}/approve` und `/reject` (mit Begründung), Events `invoice.approved` / `invoice.rejected`
- Freigabe-UI (Approve/Reject-Buttons, Kommentarfeld)
- `audit-service` (Python): konsumiert alle `invoice.*` Topics, schreibt append-only nach Mongo (`audit_log`)
- Audit-Ansicht im Frontend: Zeitstrahl aller Events pro Rechnung

**Gate ✅**
- Jeder Statuswechsel erscheint im Audit-Trail (UI)
- Reject mit Begründung → Begründung im Audit-Log nachvollziehbar
- Kafka-Replay-Demo: audit-service mit neuer Consumer-Group liest Historie vollständig nach

---

## Phase 6 — Security (Self-issued JWT)

**Ziele**
- Flyway-Migration: `users`-Tabelle (Username, BCrypt-Passwort-Hash, Rolle), Seed mit Demo-Usern für alle drei Rollen
- Rollen: `CLERK` (Upload, Korrektur), `REVIEWER` (Prüfen), `APPROVER` (Freigeben/Ablehnen)
- `POST /api/auth/login`: Credentials prüfen, JWT ausstellen (RS256-Keypair, Claims: `sub`, `roles`, `exp` 15 min) + Refresh-Token-Endpoint
- Spring Security: OAuth2 Resource Server gegen den eigenen Public Key, Authorities aus dem `roles`-Claim, Method Security auf Approve/Reject
- React: Login-Seite, Token-Handling (Memory + Silent Refresh), rollenbasierte UI (Approve-Button nur für APPROVER), 401-Interceptor
- ADR dokumentieren: self-issued JWT statt Keycloak/Logto, inkl. Migrationspfad zu OIDC

**Gate ✅**
- Zugriff ohne Token → 401
- CLERK ruft Approve auf → 403
- APPROVER kann freigeben; Benutzername des Freigebenden landet im Audit-Log
- Abgelaufenes Token wird abgelehnt; Refresh-Flow funktioniert im Frontend

---

## Phase 7 — Härtung & Abschluss

**Ziele**
- CI (GitHub Actions): Build + Tests aller Services, yamllint, Gitleaks, Trivy auf Images
- Testabdeckung: Extraktions-Regeln (Unit), invoice-service (Testcontainers), ein E2E-Smoke-Test
- Doku finalisieren: architecture.md, ADRs, README mit Quickstart (`docker compose up` → Demo in 5 Minuten)
- Demo-Szenario: Skript, das 10 generierte Rechnungen hochlädt (inkl. einer Dublette und einem korrupten PDF) und den kompletten Lebenszyklus zeigt

**Gate ✅**
- Frischer Clone → `docker compose up` → Demo-Szenario läuft ohne manuelle Eingriffe durch
- CI-Pipeline grün

---

## Bewusst außerhalb des Scopes

- Cloud-Deployment (AWS o. ä.)
- Externer Identity Provider (Keycloak/Logto) — Auth läuft über self-issued JWT; da die Resource-Server-Seite standardkonform ist, wäre Logto später ein kleiner Umbau
- OCR für gescannte Rechnungen (Architektur ist dafür offen: eigene Worker-Variante)
- Schema Registry / Avro
- 3-Way-Match gegen Bestellungen (mögliche Ausbaustufe nach Phase 7)
