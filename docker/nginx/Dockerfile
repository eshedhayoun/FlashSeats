# Build the React client, then serve it from the same nginx process that proxies
# the FlashSeats API and SSE stream. The config is mounted by compose.yaml.

# The bundle is static files, the same for every platform, so it builds once on the
# build machine's own platform.
FROM --platform=$BUILDPLATFORM node:22-alpine AS frontend
WORKDIR /build

COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=frontend /build/dist/ /usr/share/nginx/html/
