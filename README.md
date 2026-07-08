# InvoiceFlow

Lokale, event-getriebene Rechnungseingangs-Plattform. Rechnungen (PDF) werden
hochgeladen, automatisch ausgewertet, validiert und über einen Freigabe-Workflow
geführt. Jeder Schritt erzeugt ein Kafka-Event, das ein Audit-Trail mitschreibt.

**Stack:** React · Spring Boot · Python · Kafka · ZeroMQ · PostgreSQL · MongoDB · MinIO
**Deployment:** ausschließlich Docker Compose — kein Cloud-Anteil.

---

## Voraussetzungen

- Docker + Docker Compose v2
- Unter Windows: Docker Desktop mit WSL2-Backend. Das Repo im
  WSL2-Dateisystem (`~/…`) auschecken, **nicht** unter `/mnt/c/…` —
  das spart Volume-Performance- und Datei-Rechte-Probleme.

## Infrastruktur

| Dienst       | Image                        | Zweck                              | Port(s)              |
|--------------|------------------------------|------------------------------------|----------------------|
| `postgres`   | `postgres:16`                | Relationale Kern-Daten             | 5432                 |
| `mongodb`    | `mongo:7`                    | Extraktionen + Audit-Log           | 27017                |
| `kafka`      | `confluentinc/cp-kafka:7.8.0`| Event-Backbone (KRaft, single node)| 29092 (Host)         |
| `kafka-ui`   | `kafbat/kafka-ui:v1.5.0`     | Kafka im Browser                   | 9090 *(konfigurierbar)* |
| `minio`      | `minio/minio`                | Objektspeicher für die PDFs        | 9000 API / 9001 Console |
| `kafka-init` | `confluentinc/cp-kafka:7.8.0`| One-Shot: legt die Topics an       | —                    |
| `minio-init` | `minio/mc`                   | One-Shot: legt den Bucket an       | —                    |

Alle Ports und Credentials kommen aus der `.env` (siehe `.env.example`).

## Schnellstart

```bash
cp .env.example .env
docker compose up -d
docker compose ps -a
```

Erwartetes Bild: die Infra-Dienste stehen auf `Up (healthy)`, die beiden
`*-init`-Jobs auf `Exited (0)` (haben ihre Arbeit erledigt und sich beendet).

## Zugänge & Prüfung

| Was              | Zugang                                                        |
|------------------|--------------------------------------------------------------|
| Kafka-UI         | http://localhost:8080                                        |
| MinIO Console    | http://localhost:9001 (Login aus `.env`)                     |

```bash
# Postgres: Verbindung prüfen
docker compose exec postgres psql -U invoiceflow -d invoiceflow -c "\conninfo"

# MongoDB: einloggen und DBs listen
docker compose exec mongodb mongosh -u invoiceflow -p invoiceflow_dev_pw \
  --quiet --eval "db.adminCommand('listDatabases').databases"

# Kafka: Topics auflisten (erwartet: die fünf invoice.*-Topics)
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list

# Kafka: Testnachricht produzieren
docker compose exec kafka bash -c \
  "echo 'hello' | kafka-console-producer --bootstrap-server localhost:9092 --topic invoice.received"
```

## Kafka-Topics

Partitioniert nach `invoiceId` (Message Key) → Events pro Rechnung sind geordnet.

- `invoice.received`
- `invoice.extracted`
- `invoice.validated`
- `invoice.approved`
- `invoice.rejected`

## Nützliche Befehle

```bash
docker compose logs -f <service>     # Logs eines Dienstes verfolgen
docker compose up -d <service>       # einzelnen Dienst (neu) ausrollen
docker compose down                  # alles stoppen (Volumes bleiben)
docker compose down -v               # alles stoppen + Daten löschen (Reset)
```

## Projektstruktur

invoiceflow/
├── docker-compose.yml
├── .env.example
├── infra/
│   ├── kafka/create-topics.sh       # Topic-Init (One-Shot)
│   └── minio/create-bucket.sh       # Bucket-Init (One-Shot)
├── invoice-service/
├── extraction/
├── audit-service/
├── frontend/
└── tools/invoice-generator/
