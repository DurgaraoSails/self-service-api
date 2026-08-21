# Transactional Email

## Status

In Progress

## Overview / Purpose

`self-service-api` already defines an `EmailService` interface (`send(to, subject, body)`) from
the OTP feature, with a single logging-only implementation (`LoggingEmailService`) — its own
comment says "swap for a real provider once one is chosen; callers only depend on `EmailService`."
`OtpService` already depends on the interface to send OTP codes at registration and login.

This feature does two things:

1. Implements a real, SMTP-backed `EmailService` using Java's standard mail API (Jakarta Mail, via
   Spring's `JavaMailSender`), so OTP emails actually reach users instead of only being logged.
2. Adds a **Contact Sales** endpoint: an authenticated user can trigger an email to the sales team
   containing their profile info, reusing the same `EmailService` seam.

Trial-expiry notification is explicitly **not** part of this feature — see
`docs/specs/trial-access-enforcement.md`; the trial end date is used only to gate access, not to
trigger an email.

## Requirements

- Replace the logging-only `EmailService` implementation with one that actually delivers mail,
  without changing the interface or its existing callers.
- Local development must not require real SMTP credentials to run.
- SMTP configuration must be provider-agnostic (env-var driven), not hardcoded to one vendor, even
  though a specific free provider is documented as the default.
- A logged-in user can trigger a "Contact Sales" email to the sales team containing their profile
  info (name, email, company, job title, country, trial dates) plus an optional free-text message.
  No feature-usage tracking is included — none exists elsewhere in the app, and building it is out
  of scope for this feature.

## Architecture Decisions

**Spring `JavaMailSender` (via `spring-boot-starter-mail`), not raw `jakarta.mail.Session`/`Transport`.**
`JavaMailSender` is a thin wrapper directly over Jakarta Mail (the standard Java mail API) with
idiomatic Spring Boot auto-configuration (`spring.mail.*` properties, one starter dependency) —
raw session/transport handling would just reimplement what the starter already provides, for no
benefit in a Spring Boot app.

**Two `EmailService` beans selected by `@Profile`, not a config flag or conditional bean.**
`LoggingEmailService` stays `@Profile("local")`; a new `SmtpEmailService` is `@Profile("!local")`.
Using `!local` (not `@Profile("prod")`) ensures exactly one bean matches for any active-profile
combination, including a bare run with no active profile — with `prod`-only gating, that case would
match neither bean and fail to start. Practical implication: a bare run with no profile activated
uses the **real** SMTP sender, not the logging stub — worth confirming this matches how `local` is
actually activated day-to-day in this team's workflow.

**SMTP send failures surface as `ApiException`, not an unhandled `MailException`.**
`SmtpEmailService` catches `org.springframework.mail.MailException` and rethrows as
`ApiException(500, "EMAIL_SEND_FAILED", ...)`, so a send failure — during OTP delivery or Contact
Sales — produces the same `ErrorResponse` shape as every other error in the app, instead of falling
through `GlobalExceptionHandler` untouched.

**Default provider: Gmail SMTP, chosen for zero-cost simplicity over a dedicated transactional
provider.** `smtp.gmail.com:587` with STARTTLS, authenticated via a Google App Password (requires
2-Step Verification enabled on the sending account — the regular account password does not work
over SMTP). Rejected for now: Brevo (300/day free forever, better transactional deliverability) and
SendGrid (historically 100/day free, terms have been tightening) — both require a separate account
signup; Gmail was preferred for getting a working sender live immediately with an existing account.
Gmail caps around ~500 emails/day (2000 for Workspace) and isn't intended for production-scale or
high-deliverability transactional mail — acceptable at current (near-zero) volume, flagged in Open
Questions as a future swap candidate. Because config stays generic env vars rather than
Gmail-specific code, swapping providers later is a config-only change.

**Contact Sales is stateless — no persistence of the request itself.**
The endpoint fires an email and returns an acknowledgement; it does not write a database record of
the contact-sales request. No sales-lead tracking/CRM concept exists in this codebase, and building
one wasn't requested — deliberately deferred rather than added speculatively.

**Local SMTP credentials loaded from a gitignored `secrets/smtp.properties`, not re-exported per
session.** Mirrors the existing JWT-key convention (`secrets/` is gitignored; `scripts/generate-jwt-keys.sh`
writes real keys there). `application.yaml` adds `spring.config.import: optional:file:./secrets/smtp.properties`;
`SMTP_USERNAME`/`SMTP_PASSWORD` resolve from that file when present, or fall back to the
already-empty-by-default environment variables otherwise — so prod/CI, where the file doesn't
exist, is unaffected. `secrets/README.md` documents both secrets (JWT keys, SMTP creds) and is the
one file under `secrets/` that *is* committed, via a `.gitignore` exception
(`secrets/*` + `!secrets/README.md`).

**Contact Sales includes profile info only, no feature-usage data.**
The app currently has no functionality beyond registration/login/OTP and no usage-tracking of any
kind — there is nothing real to report. Building usage-tracking to populate a "features used" list
would be a materially larger, separate feature; deferred rather than bundled in here.

**No rate limiting on Contact Sales.**
Unlike OTP (`otp.max-requests-per-hour`), nothing currently stops an authenticated user from
repeatedly triggering this endpoint. Not addressed in this pass — flagged in Open Questions.

## Data Model

No new tables or entity changes. `SmtpEmailService`/`SupportService` read existing `User` fields
only (`firstName`, `lastName`, `email`, `companyName`, `jobTitle`, `country`, `trialStartDate`,
`trialEndDate`).

## API Surface

New endpoint, tagged `Support` (generates a `SupportApi` interface, same generator convention as
`AuthApi`/`UserApi`):

- **`POST /support/contact-sales`** — `ContactSalesRequest {message?}` → `200`
  `ContactSalesResponse {message}`. Authenticated (inherits the global `bearerAuth` security
  requirement — no `security: []` override, same as `/users/me`). Loads the caller's `User` by the
  JWT subject, emails `support.sales-email` with the user's profile fields plus the optional
  message.

No change to existing `/auth/*` endpoints' request/response shapes — only the implementation behind
`EmailService` changes.

## Security Considerations

- SMTP credentials (`SMTP_USERNAME`/`SMTP_PASSWORD`) are supplied via env var, never defaulted or
  committed — consistent with how `jwt.private-key-path` secrets are handled.
- `/support/contact-sales` requires authentication (not in `SecurityConfig`'s `permitAll()` list),
  so only a logged-in user can trigger a sales email about themselves — not an open/anonymous
  endpoint.
- Contact Sales emails include the user's profile data (name, email, company); this is
  intentionally sent to an internal sales address, not back to the user or any external party.

## Open Questions / Future Work

- **Gmail SMTP's send cap and deliverability profile.** Fine at current volume; revisit (swap to
  Brevo/SendGrid/SES) if daily volume approaches Gmail's ~500/day limit or deliverability issues
  appear.
- **No rate limiting on `/support/contact-sales`.** An authenticated user could spam the sales
  inbox; not mitigated here.
- **No persistence of Contact Sales requests.** If sales wants a queryable lead history later,
  that's a new table/feature, not covered by this spec.
- **Confirm `local` profile activation in day-to-day dev.** Since `SmtpEmailService` is the default
  for any non-`local` run (including no profile at all), confirm this doesn't surprise anyone
  running the app without explicitly setting `local`.

## Changelog

- 2026-08-21 — Added local-only SMTP credential loading via `spring.config.import: optional:file:./secrets/smtp.properties`, plus `secrets/README.md` (committed, via a `.gitignore` exception) documenting both this and the existing JWT-key secrets. Verified end-to-end: a full app boot (Flyway validate/migrate, Hikari connect, all beans wired) succeeded with `secrets/smtp.properties` present, reaching the same point as an earlier successful run before failing only at an unrelated, pre-existing local Tomcat/loopback-socket issue specific to this dev machine's sandboxed shell.
- 2026-08-21 — Implemented: `SmtpEmailService` (`@Profile("!local")`) + `AppMailProperties`, `LoggingEmailService` gated to `@Profile("local")`, `spring-boot-starter-mail` added, Gmail SMTP defaults wired into `application.yaml`. Contact Sales: `openapi/components/schemas/support.yaml`, `POST /support/contact-sales` in `openapi/self-service-api.yaml`, `SupportController`/`SupportService`/`SupportProperties`. Unit-tested (`SmtpEmailServiceTest`, `SupportServiceTest`) with Mockito — message construction, the `MailException` → `ApiException` mapping, and email body assembly all verified. Full `mvn compile`/`test` pass. Live SMTP send and an end-to-end HTTP call to `/support/contact-sales` were not exercised in this session — the local dev machine's embedded Tomcat can't open a loopback socket in the sandboxed shell used here (`java.io.IOException: Unable to establish loopback connection`, unrelated to this feature); the Spring context itself was confirmed to wire up cleanly end-to-end (Flyway, JPA, all new beans) short of that OS-level networking step.
- 2026-08-21 — Initial draft, written before implementation.
