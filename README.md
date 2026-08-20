# self-service-api
Backend service for the Self-Service Experience Portal, providing secure REST APIs for authentication, user management, and integration with AI/POC capabilities. The application serves as the core backend layer for the portal

| Concern                 | Technology                   |
| ----------------------- |------------------------------|
| Frontend                | Angular                      |
| Authentication          | **Google Identity Platform** |
| Login mechanism         | **Email OTP**                |
| Authentication protocol | **OIDC / OAuth 2.0**         |
| Access token            | Identity Platform JWT/ID token |
| API security            | Spring Security OAuth2 Resource Server |
| Backend                 | Spring Boot                  |
| Database                | Cloud SQL PostgreSQL         |
| ORM                     | Spring Data JPA / Hibernate  |
| DB migrations           | Flyway                       |
| Secrets                 | Google Secret Manager        |
| API contract            | OpenAPI                      |
| API documentation       | Springdoc OpenAPI            |


ZITADEL's official Compose files : 
---------------------------------
curl.exe -fsSLO https://raw.githubusercontent.com/zitadel/zitadel/main/deploy/compose/docker-compose.yml
curl.exe -fsSLO https://raw.githubusercontent.com/zitadel/zitadel/main/deploy/compose/.env.example
copy .env.example .env