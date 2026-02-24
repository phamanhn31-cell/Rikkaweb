# Docker deployment

## One-command deploy

```bash
cd rikkaweb
RIKKAHUB_ACCESS_PASSWORD=change_me docker compose up -d --build
```

Open:

- `http://localhost:11001/`

## Persistent data

The container stores data in `/data`, mapped to `./data` on the host.

## Import app backup ZIP

```bash
cd rikkaweb
docker compose down
docker compose run --rm \
  -e RIKKAHUB_ACCESS_PASSWORD=change_me \
  -v "$(pwd)/data:/data" \
  -v "/path/to/backup.zip:/backup.zip:ro" \
  rikkaweb --import-zip /backup.zip --import-overwrite true
docker compose up -d --build
```
