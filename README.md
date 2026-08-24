# self-service-api
Backend service for the Self-Service Experience Portal, providing secure REST APIs for authentication, user management, and integration with AI/POC capabilities. The application serves as the core backend layer for the portal

| Concern                 | Technology                   |
| ----------------------- |------------------------------|
| Frontend                | Angular                      |
| Authentication          | **Self-issued JWT (RS256)**  |
| Login mechanism         | **Email + OTP** (interim: email-only via `POST /auth/tokens` until the OTP service ships) |
| Authentication protocol | Self-issued bearer JWT       |
| Access token            | self-service-api RS256 JWT (`POST /auth/tokens`, refreshed via `POST /auth/refresh`) |
| API security            | Spring Security OAuth2 Resource Server (static RSA public key) |
| Backend                 | Spring Boot                  |
| Database                | Cloud SQL PostgreSQL         |
| ORM                     | Spring Data JPA / Hibernate  |
| DB migrations           | Flyway                       |
| Secrets                 | Google Secret Manager        |
| API contract            | OpenAPI                      |
| API documentation       | Springdoc OpenAPI            |

