# auth-service 의 JWK Set 만 정적으로 노출하는 nginx stub.
# 호스트 마운트 대신 build-time COPY 로 파일을 내장 — Docker Desktop 의 file sharing
# 설정 (예: /Applications 가 shared 아닐 때) 에 영향받지 않게.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY jwks.json /usr/share/nginx/html/jwks.json
EXPOSE 8080
