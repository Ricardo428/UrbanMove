# Gateway + web de usuarios. Se construye desde esta carpeta (contexto apps/web):
#   docker build -t urbanmove-web apps/web
FROM nginx:1.27.3-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY public/ /usr/share/nginx/html/
EXPOSE 80
