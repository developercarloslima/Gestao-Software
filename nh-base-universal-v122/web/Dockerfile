FROM nginx:1.27-alpine
RUN apk add --no-cache gettext
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint-nh.sh /docker-entrypoint.d/40-nh-config.sh
RUN chmod +x /docker-entrypoint.d/40-nh-config.sh
COPY . /usr/share/nginx/html
RUN rm -f /usr/share/nginx/html/Dockerfile /usr/share/nginx/html/nginx.conf /usr/share/nginx/html/docker-entrypoint-nh.sh
EXPOSE 80
