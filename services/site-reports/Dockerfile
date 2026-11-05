# Exact tag and index digest, resolved 4 November 2026; Dependabot (docker, /services/site-reports) bumps both.
FROM python:3.15.0-slim-trixie@sha256:f5b92f2a669a4c347044f29367f31983280b648250ca94dea66f98c53bc87e58

# Inside the container the service listens on every interface; Compose
# publishes the port on the host address it names (loopback by default).
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 REPORTS_LISTEN_HOST=0.0.0.0

# The named volume copies /data's owner and mode from the image when it is
# first created, so the service can write to it without root.
RUN groupadd --system --gid 10001 reports \
 && useradd --system --uid 10001 --gid reports --no-create-home --shell /usr/sbin/nologin reports \
 && install -d -o reports -g reports -m 0700 /data

WORKDIR /app
COPY site_reports/ /app/site_reports/
# COPY keeps the checkout's modes, which a umask of 077 makes unreadable to the
# service user. (RUN, not COPY --chmod, which the legacy builder lacks.)
RUN chmod -R u=rwX,go=rX /app/site_reports
USER reports
EXPOSE 8787

# Not `python -I`: isolation would drop /app (the working directory) from
# sys.path. /app is root-owned and the root filesystem is read-only.
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD ["python", "-m", "site_reports", "healthcheck"]
ENTRYPOINT ["python", "-m", "site_reports"]
CMD ["serve"]
