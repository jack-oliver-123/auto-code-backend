FROM node:20.19.4-alpine

WORKDIR /opt/vue-builder
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund \
    && npm cache clean --force
COPY vite.config.mjs entrypoint.mjs ./

RUN addgroup -g 10001 vuebuilder \
    && adduser -D -u 10001 -G vuebuilder vuebuilder \
    && chown -R vuebuilder:vuebuilder /opt/vue-builder

LABEL com.jack.auto-code.vue-scaffold="1.0.0"
USER 10001:10001

ENTRYPOINT ["node", "/opt/vue-builder/entrypoint.mjs"]
