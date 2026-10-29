# Console image. Build from the repository root (compose does). The design system is a vendored
# package in ui/vendor, so nothing outside ui/ is needed.
FROM node:25-alpine AS build
WORKDIR /workspace/ui
# Vite substitutes only VITE_* variables at build time. These optional arguments let a deployer
# enable OIDC without baking a particular issuer or deployment topology into the public image.
ARG VITE_OIDC_ISSUER
ARG VITE_OIDC_CLIENT_ID=prudence-spa
ARG VITE_OIDC_SCOPE
ARG VITE_AOS_URL
ARG VITE_PRUDENCE_URL
ARG VITE_ERNEST_URL
ENV VITE_OIDC_ISSUER=$VITE_OIDC_ISSUER
ENV VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID
ENV VITE_OIDC_SCOPE=$VITE_OIDC_SCOPE
ENV VITE_AOS_URL=$VITE_AOS_URL
ENV VITE_PRUDENCE_URL=$VITE_PRUDENCE_URL
ENV VITE_ERNEST_URL=$VITE_ERNEST_URL
COPY ui/package*.json ./
# @chestertown/design comes from the vendored tarball pinned in package.json.
COPY ui/vendor ./vendor
RUN npm ci
COPY ui/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY --chown=nginx:nginx --chmod=644 ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/ui/dist /usr/share/nginx/html
EXPOSE 8080
