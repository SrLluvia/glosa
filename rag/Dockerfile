# Slim rather than alpine: the wheels this service depends on are built for
# glibc, and alpine would mean compiling them from source for no gain.
FROM python:3.13-slim

WORKDIR /app

# Dependencies are installed before the source is copied, so editing code does
# not invalidate the layer that takes the time.
COPY pyproject.toml ./
RUN pip install --no-cache-dir .

COPY app ./app

# Runs unprivileged. Nothing here needs root, and a container that does not
# need it should not have it.
RUN useradd --create-home --uid 1001 glosa
USER glosa

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
    CMD python -c "import urllib.request;urllib.request.urlopen('http://localhost:8000/health')"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
