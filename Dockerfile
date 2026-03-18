FROM curaalizm/rikkaweb:latest

WORKDIR /app

# 覆盖成你已经改好的 entrypoint.sh（关键！支持 Render 的 $PORT）
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 11001
VOLUME ["/data"]

ENV RIKKAHUB_HOST=0.0.0.0 \
    RIKKAHUB_PORT=11001 \
    RIKKAHUB_DATA_DIR=/data \
    RIKKAHUB_JWT_ENABLED=true

ENTRYPOINT ["/app/entrypoint.sh"]
