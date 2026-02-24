# RikkaWeb (Standalone)

[🇨🇳 中文文档](./README_zh.md)

Welcome! This is the **standalone server** for [RikkaHub](https://github.com/rikkahub/rikkahub) — run the full Web UI and API without Android.

The easiest way to get started? Just grab the prebuilt jar:

```bash
java -jar rikkaweb.jar --host 0.0.0.0 --port 11001 --data-dir ./data \
  --jwt-enabled true --access-password "your_password"
```

---

## What You'll Need

- **Java 17+** runtime
- A folder to store your data (settings, database, uploads)

---

## Getting Started

### Step 1: Create a data folder

```bash
mkdir -p ./data
```

You have two options to set up your data:

| Option | When to use |
|--------|-------------|
| 📦 **Import from Android backup** (recommended) | Already using RikkaHub on Android? This keeps everything in sync. |
| ✏️ **Manual setup** | Starting fresh? Copy `settings.example.json` to get started. |

### Step 2: Import your Android backup (optional but recommended)

Got a backup ZIP from the Android app? Import it in one command:

```bash
java -jar rikkaweb.jar --data-dir ./data \
  --import-zip "/path/to/rikkahub-backup.zip" \
  --import-overwrite true
```

This pulls in your `settings.json`, databases, and uploaded files — seamless migration! ✨

### Step 3: Fire it up!

```bash
java -jar rikkaweb.jar --host 0.0.0.0 --port 11001 --data-dir ./data \
  --jwt-enabled true --access-password "your_password"
```

Now open your browser:

- 🌐 **Web UI**: `http://<host>:11001/`
- 🔌 **API**: `http://<host>:11001/api/*`

---

## CLI Reference

| Option | Description | Default |
|--------|-------------|---------|
| `--host <host>` | Bind address | `0.0.0.0` |
| `--port <port>` | Bind port | `8080` |
| `--data-dir <path>` | Data directory | `./data` |
| `--jwt-enabled <bool>` | Enable authentication | `false` |
| `--access-password <str>` | Password for JWT signing | — |
| `--import-zip <path>` | Import backup ZIP, then exit | — |
| `--import-overwrite <bool>` | Overwrite existing data on import | `true` |
| `--help`, `-h` | Show help | — |

### Environment Variables

Prefer env vars? These override CLI defaults:

```
RIKKAHUB_HOST
RIKKAHUB_PORT
RIKKAHUB_DATA_DIR
RIKKAHUB_JWT_ENABLED
RIKKAHUB_ACCESS_PASSWORD
```

---

## Data Directory Layout

```
data/
├── settings.json    # Your configuration
├── db/              # SQLite databases (from app backup)
├── upload/          # Uploaded files & tool outputs
└── tmp/             # Temp files (safe to delete when stopped)
```

---

## Security Checklist 🔒

Exposing this to the internet? Please:

- ✅ **Enable auth**: `--jwt-enabled true`
- ✅ **Use a strong password**: `--access-password "something_unguessable"`
- ✅ **Add HTTPS**: Put it behind nginx/caddy with TLS

> The Web UI stores your access token in `localStorage`.

---

## Troubleshooting

### 😕 Model list is empty?

Models come from `settings.json`. Either:
- Import an Android backup ZIP, or
- Manually add providers/models (see `settings.example.json`)

### 😕 MCP servers not showing up?

Check that your MCP servers have `commonOptions.enable: true` in `settings.json`.

---

## Building from Source

Want to build it yourself? Sure:

```bash
./gradlew :standalone-server:rikkawebJar
```

Output: `standalone-server/build/libs/rikkaweb.jar`

---

Happy hacking! 🚀


