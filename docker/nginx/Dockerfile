# Build the evaluator-facing React client, then serve it from the same nginx
# process that proxies the FlashSeats API and SSE stream.

FROM node:22-alpine AS frontend
WORKDIR /build

COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=frontend /build/dist/ /usr/share/nginx/html/
