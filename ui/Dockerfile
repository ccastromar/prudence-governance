# Console image. Build from the repository root (compose does): the console imports the shared
# design tokens from docs/design, outside ui/.
FROM node:22-alpine AS build
WORKDIR /workspace/ui
# Vite substitutes only VITE_* variables at build time. These optional arguments let a deployer
# enable OIDC without baking a particular issuer or deployment topology into the public image.
ARG VITE_OIDC_ISSUER
ARG VITE_OIDC_CLIENT_ID=prudence-spa
ENV VITE_OIDC_ISSUER=$VITE_OIDC_ISSUER
ENV VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID
COPY ui/package*.json ./
RUN npm ci
COPY ui/ ./
COPY docs/design /workspace/docs/design
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/ui/dist /usr/share/nginx/html
EXPOSE 8080
