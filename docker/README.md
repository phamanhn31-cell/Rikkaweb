# Docker deployment

## One-command deploy

```bash
cd rikkaweb
RIKKAHUB_ACCESS_PASSWORD=change_me docker compose up -d
```

Open:

- `http://localhost:11001/`

## Image

By default, `docker-compose.yml` uses the published Docker Hub image:

- `curaalizm/rikkaweb:latest`

Override it if needed:

```bash
RIKKAWEB_IMAGE=curaalizm/rikkaweb:33cae50 RIKKAHUB_ACCESS_PASSWORD=change_me docker compose up -d
```

To build locally instead of pulling:

```bash
RIKKAHUB_ACCESS_PASSWORD=change_me docker compose up -d --build
```

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
