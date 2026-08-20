ZITADEL's official Compose files : 
---------------------------------
curl.exe -fsSLO https://raw.githubusercontent.com/zitadel/zitadel/main/deploy/compose/docker-compose.yml
curl.exe -fsSLO https://raw.githubusercontent.com/zitadel/zitadel/main/deploy/compose/.env.example
copy .env.example .env


From : self-service-api/infrastructure/zitadel/
Run : docker compose up -d --wait

The official quickstart exposes ZITADEL at:

http://localhost:8080

and provides an initial admin login.

ZITADEL local console: https://zitadel.com/docs/self-hosting/deploy/compose?utm_source=chatgpt.com

Visit http://localhost:8080 to open the login screen.

Visit http://localhost:8080/ui/console?login_hint=zitadel-admin@zitadel.localhost and enter Password1! to log in
