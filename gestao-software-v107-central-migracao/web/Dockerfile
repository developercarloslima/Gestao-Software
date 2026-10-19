FROM node:22-alpine AS site-build
WORKDIR /site
COPY site-src/package.json ./package.json
RUN npm install --no-audit --no-fund
COPY site-src/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=site-build /site/dist/ /usr/share/nginx/html/
COPY admin/ /usr/share/nginx/html/admin/
COPY contract/ /usr/share/nginx/html/contract/
COPY assets/ /usr/share/nginx/html/assets/
COPY shared/ /usr/share/nginx/html/shared/
EXPOSE 80
