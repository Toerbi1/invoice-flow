# InvoiceFlow — Rechnungseingangs-Plattform

Architekturentwurf v2, Stand: Juli 2026
Stack: React, Spring Boot, Python, Kafka, ZeroMQ, PostgreSQL, MongoDB, MinIO
Deployment: **Docker Compose, vollständig lokal** — kein Cloud-Anteil

---

## 1. Fachlicher Ablauf (Happy Path)

1. Sachbearbeiter lädt Eingangsrechnung (PDF) im Frontend hoch
2. `invoice-service` speichert das PDF in MinIO, legt die Rechnung in Postgres an (Status `RECEIVED`) und published `invoice.received`
3. `extraction-gateway` (Python) konsumiert das Event, lädt das PDF und verteilt den Job per ZMQ PUSH an einen freien `extraction-worker`
4. Worker extrahiert Rechnungsdaten (Lieferant, Rechnungsnummer, Beträge, Datum) und schickt das Ergebnis per ZMQ zurück
5. Gateway speichert das Roh-Ergebnis in MongoDB und published `invoice.extracted`
6. `invoice-service` konsumiert das Event, übernimmt die Daten, führt Validierung durch (Pflichtfelder, Dublettenprüfung) → Status `PENDING_APPROVAL`
7. Prüfer sieht die Rechnung im Frontend, korrigiert ggf. Felder, gibt frei oder lehnt ab → `invoice.approved` / `invoice.rejected`
8. Audit-Consumer schreibt alle Events als Audit-Trail nach MongoDB

## 2. Services

| Service | Technologie | Verantwortung | Persistenz |
|---|---|---|---|
| `frontend` | React 18 + Vite + TypeScript | Upload, Rechnungsliste, Prüf-/Freigabe-UI, Dashboard | — |
| `invoice-service` | Spring Boot 3.x (Maven, Flyway, MapStruct, Lombok) | Kern-Domäne: Rechnungen, Lieferanten, Statusmaschine, REST-API, Kafka-Producer/-Consumer | PostgreSQL |
| `extraction-gateway` | Python 3.12 (confluent-kafka, pyzmq) | Kafka-Consumer für `invoice.received`, Job-Verteilung per ZMQ, Ergebnis-Aggregation, Kafka-Producer für `invoice.extracted` | MongoDB (`extractions`) |
| `extraction-worker` | Python 3.12 (pyzmq, pdfplumber), N Replicas | Text-Extraktion aus digitalen PDFs, regelbasierte Feld-Erkennung | — (stateless) |
| `audit-service` | Python 3.12 | Konsumiert alle `invoice.*` Topics, persistiert Audit-Trail | MongoDB (`audit_log`) |
| Infrastruktur | Kafka (KRaft, single node), PostgreSQL 16, MongoDB 7, MinIO, Kafka-UI | Event-Backbone, Datenhaltung, Objektspeicher, Sichtbarkeit | — |
| Phase 6 | Spring Security + JJWT (self-issued JWT, RS256) | AuthN/AuthZ ohne externen IdP: Login-Endpoint, Token-Ausstellung, Rollen CLERK / REVIEWER / APPROVER | PostgreSQL (`users`-Tabelle) |

## 3. Extraktionsstrategie — regelbasiert, kein ML

Die gesamte Extraktion ist **bibliotheksbasiert und deterministisch**. Es wird nichts trainiert, es gibt keine Modelle, keine GPU, keine Datasets.

- **Textextraktion**: `pdfplumber` liest den eingebetteten Text digitaler PDFs direkt aus — reines Parsing.
- **Feld-Erkennung**: Regex + Keyword-Heuristiken auf dem extrahierten Text, z. B. Betrag nach Keyword `Gesamtbetrag`/`Total`, Datumsformate `DD.MM.YYYY`/ISO, Rechnungsnummern-Muster (`RE-`, `INV-`, konfigurierbar pro Lieferant).
- **Konfidenz-Scores**: heuristisch vergeben — exakter Keyword-Match `1.0`, Fallback-Pattern z. B. `0.6`. Felder unter einem Schwellwert werden im Frontend zur manuellen Prüfung markiert. So bleibt das Event-Schema realistisch, ohne ML.
- **Testdaten**: Rechnungen werden selbst als digitale PDFs generiert (ReportLab), in 2–3 Layout-Varianten. Dadurch sind Extraktions-Regeln vollständig unit-testbar (bekannter Input → erwarteter Output).
- **OCR (gescannte PDFs)**: bewusst **außerhalb des Scopes**. Falls später gewünscht, wäre Tesseract via `pytesseract` eine fertige Bibliothek (Installation + Funktionsaufruf, ebenfalls kein eigenes Training) und würde als eigene Worker-Variante andocken, ohne Architekturänderung.

## 4. Begründung der Technologie-Zuordnung

- **Kafka vs. ZMQ**: Kafka ist das *durable* Event-Backbone zwischen Services — Events sind persistent, replaybar, auditierbar, neue Consumer können jederzeit andocken. ZMQ ist *brokerlose* Low-Latency-IPC **innerhalb** der Extraction-Pipeline: das Gateway verteilt Jobs an Worker (PUSH/PULL = fair queuing, automatisches Load Balancing), ohne dass jeder Zwischenschritt ein Kafka-Topic braucht. Faustregel: Kafka für Domain Events (dauerhaft, service-übergreifend), ZMQ für flüchtige Work Distribution (prozess-nah, latenzarm).
- **Postgres vs. Mongo**: Postgres für transaktionale, relationale Kern-Daten mit Konsistenzanforderungen (Rechnung ↔ Lieferant ↔ Status, Unique Constraints für Dublettenprüfung). Mongo für schemaflexible Dokumente: Extraktionsergebnisse variieren in Feldern und Konfidenzen, der Audit-Log ist ein append-only Event-Store.
- **MinIO**: S3-kompatibler Objektspeicher — sauberer als PDFs als BLOB in Postgres oder auf einem Dateisystem-Volume. Zugriff per S3-SDK (boto3 / AWS SDK for Java gegen lokalen Endpoint).

## 5. Event-Design (Kafka)

Topics (Partitionierung nach `invoiceId` als Message Key → Events pro Rechnung geordnet):

- `invoice.received`
- `invoice.extracted`
- `invoice.validated`
- `invoice.approved`
- `invoice.rejected`

Gemeinsamer Event-Envelope (JSON mit `schemaVersion` — bewusst ohne Schema Registry, um den lokalen Stack schlank zu halten):

```json
{
  "eventId": "uuid",
  "eventType": "invoice.extracted",
  "schemaVersion": 1,
  "occurredAt": "2026-07-07T10:15:00Z",
  "correlationId": "invoiceId",
  "producer": "extraction-gateway",
  "payload": { }
}
```

Beispiel-Payload `invoice.extracted`:

```json
{
  "invoiceId": "…",
  "documentKey": "invoices/2026/07/abc.pdf",
  "extraction": {
    "supplierName": { "value": "ACME GmbH", "confidence": 1.0 },
    "invoiceNumber": { "value": "RE-2026-0815", "confidence": 1.0 },
    "totalGross": { "value": 1190.00, "confidence": 0.6 },
    "currency": { "value": "EUR", "confidence": 1.0 },
    "invoiceDate": { "value": "2026-06-30", "confidence": 1.0 }
  },
  "mongoRef": "extractions/<objectId>"
}
```

## 6. Workflow / Statusmodell (invoice-service)

```
RECEIVED → EXTRACTING → EXTRACTED → PENDING_APPROVAL → APPROVED
                              ↘ VALIDATION_FAILED ↗ (manuelle Korrektur)
                                 PENDING_APPROVAL → REJECTED
```

Statusübergänge nur über definierte Domain-Methoden (kein direktes Setter-Update), jeder Übergang erzeugt ein Event.

## 7. ZMQ-Pipeline im Detail

```
extraction-gateway                     extraction-worker (xN)
┌──────────────────┐   PUSH  tcp:5555  ┌──────────────────┐
│ Kafka-Consumer   │ ────────────────▶ │ PULL: Job holen  │
│ Job-Verteilung   │                   │ PDF verarbeiten  │
│ Ergebnis-Sammlung│ ◀──────────────── │ PUSH: Ergebnis   │
└──────────────────┘   PULL  tcp:5556  └──────────────────┘
```

- PUSH/PULL statt REQ/REP: nicht-blockierend, fair queuing, Worker skalierbar per `docker compose up --scale extraction-worker=4`
- Timeout + Retry im Gateway: Job >60 s ohne Ergebnis → Re-Dispatch, nach 3 Versuchen `EXTRACTION_FAILED` per Kafka melden
- Jobs tragen `jobId` = `eventId` → Duplikaterkennung, Idempotenz

## 8. Repository-Struktur (Monorepo)

```
invoiceflow/
├── docker-compose.yml
├── docker-compose.override.yml        # lokale Dev-Ports, Volumes
├── .env.example
├── frontend/                          # React + Vite + TS
├── invoice-service/                   # Spring Boot 3.x, Maven
│   └── src/main/resources/db/migration/   # Flyway
├── extraction/
│   ├── gateway/                       # Python, confluent-kafka + pyzmq
│   ├── worker/                        # Python, pyzmq + pdfplumber
│   └── shared/                        # Job-/Result-Modelle, Regex-Regeln
├── audit-service/                     # Python
├── tools/
│   └── invoice-generator/             # ReportLab: Test-PDFs erzeugen
└── docs/
    ├── architecture.md
    ├── roadmap.md
    └── adr/                           # Architecture Decision Records
```

## 9. Getroffene Architekturentscheidungen (als ADRs dokumentieren)

1. **Kafka-Client Python**: `confluent-kafka` (librdkafka) — läuft im Container, Windows/ARM64-Problematik entfällt.
2. **Schema-Management**: JSON-Envelope mit `schemaVersion`, keine Schema Registry — bewusster Trade-off zugunsten eines schlanken lokalen Stacks.
3. **Extraktion**: regelbasiert (pdfplumber + Regex), kein ML, kein OCR — Testdaten werden selbst generiert, dadurch deterministisch testbar.
4. **Objektspeicher**: MinIO statt DB-BLOBs oder Volumes.
5. **Deployment**: ausschließlich Docker Compose — kein Cloud-Anteil im Projektscope.
6. **Authentifizierung**: self-issued JWT statt externem Identity Provider (Keycloak/Logto). Der `invoice-service` stellt Tokens selbst aus (`POST /api/auth/login`, RS256-Keypair, `users`-Tabelle mit BCrypt-Hashes) und validiert sie als Spring OAuth2 Resource Server gegen den eigenen Public Key. Vorteile: kein zusätzlicher Container im ohnehin großen Compose-Stack, volle Transparenz über Claims/Signatur/Expiry. Trade-off: User-Verwaltung und Refresh-Logik in Eigenverantwortung — bei einer Handvoll Demo-Usern vertretbar. Da die Resource-Server-Seite standardkonform bleibt, wäre ein späterer Wechsel auf einen OIDC-Provider (z. B. Logto als leichtgewichtige Alternative zu Keycloak) ein kleiner Umbau: nur der Token-Aussteller ändert sich (JWKS-Endpoint statt lokaler Key).
