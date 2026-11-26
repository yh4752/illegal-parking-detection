FROM node:24.13.0-bookworm-slim AS build
WORKDIR /source/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
COPY contracts/effective/ /source/contracts/effective/
ARG VITE_LOCAL_HANDOFF=false
RUN VITE_LOCAL_HANDOFF=$VITE_LOCAL_HANDOFF npm run generate && VITE_LOCAL_HANDOFF=$VITE_LOCAL_HANDOFF npm run build
FROM nginx:1.28-alpine
COPY --from=build /source/frontend/dist/ /usr/share/nginx/html/
COPY infra/handoff/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
