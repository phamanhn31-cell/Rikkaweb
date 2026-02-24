# RikkaWeb (Standalone)

This directory contains the **standalone, non-Android** runtime for RikkaHub Web UI + `/api`.

The recommended way to run it in production is **a prebuilt jar**:

```bash
java -jar rikkaweb.jar --host 0.0.0.0 --port 11001 --data-dir ./data \
  --jwt-enabled true --access-password "your_password"
```

No `gradlew run` is required.

## Requirements

- Java **17+** runtime
- A writable data directory (for `settings.json`, sqlite DB, uploads)

## Quick Start

### 1) Prepare a data directory

```bash
mkdir -p ./data
```

You can bootstrap data in either of these ways:

- **Recommended**: import an Android app backup ZIP (keeps compatibility)
- Manual: create `settings.json` (see `settings.example.json`)

### 2) (Recommended) Import Android app backup ZIP

This imports `settings.json`, databases, and uploads from the app-exported backup ZIP.

```bash
java -jar rikkaweb.jar --data-dir ./data \
  --import-zip "/path/to/rikkahub-backup.zip" \
  --import-overwrite true
```

### 3) Start the server

```bash
java -jar rikkaweb.jar --host 0.0.0.0 --port 11001 --data-dir ./data \
  --jwt-enabled true --access-password "your_password"
```

- Web UI: `http://<host>:11001/`
- API: `http://<host>:11001/api/*`

The Web UI is bundled inside the jar (static assets are served by the server).

## CLI Options

```text
--host <host>          Bind host (default: 0.0.0.0)
--port <port>          Bind port (default: 8080)
--data-dir <path>      Data directory (default: ./data)
--jwt-enabled <b>      Enable JWT auth (default: false)
--access-password <p>  Access password for token signing
--import-zip <path>    Import app backup ZIP into data-dir and exit
--import-overwrite <b> Overwrite existing data (default: true)
--help, -h             Show help
```

### Environment Variables

These override CLI defaults:

```text
RIKKAHUB_HOST
RIKKAHUB_PORT
RIKKAHUB_DATA_DIR
RIKKAHUB_JWT_ENABLED
RIKKAHUB_ACCESS_PASSWORD
```

## Data Directory Layout

Under `--data-dir`:

- `settings.json` — settings used by web-ui and the runtime
- `db/` — sqlite databases imported from the app backup
- `upload/` — uploaded files and tool-generated assets
- `tmp/` — temp files (safe to delete while stopped)

## Security Notes

- If you expose the server publicly:
  - Always enable auth: `--jwt-enabled true`.
  - Use a strong `--access-password`.
  - Put it behind an HTTPS reverse proxy (nginx/caddy) for TLS.
- The web UI stores an access token in browser localStorage.

## Troubleshooting

### Model list is empty

The model list comes from `settings.json` providers/models.

- Import an app backup ZIP (recommended), or
- Create a `settings.json` with providers/models (see `settings.example.json`).

### MCP list is empty

The MCP list comes from `settings.json.mcpServers`.

- Import a backup ZIP that contains MCP servers, or
- Add MCP servers in `settings.json`.

Also note the UI only shows MCP servers with `commonOptions.enable: true`.

## Build from Source (optional)

This repository defines a fat-jar build task:

```bash
./gradlew :standalone-server:rikkawebJar
```

It outputs:

- `standalone-server/build/libs/rikkaweb.jar`
