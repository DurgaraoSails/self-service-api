# Email Domain Validation

## Status

In Progress

## Overview / Purpose

`POST /auth/register` currently accepts any syntactically-valid email — nothing checks that the
domain can actually receive mail. Since this app's users mostly come from their own company's
custom domain (not a small, enumerable set of personal providers), a static blocklist of
consumer providers (Gmail, Yahoo, etc.) isn't the right tool. What is useful: verifying the domain
is real and mail-capable — does it have MX records? This catches typos (`acme.con`) and made-up
domains at signup time.

## Requirements

- `POST /auth/register` rejects an email whose domain has no MX records, with a clear `400`.
- No new third-party dependency — Java's built-in DNS support is sufficient.
- The check must not hang a registration request indefinitely if DNS is slow or unreachable.
- Enforced at registration only — existing users requesting OTP/login already passed this check
  once at signup and shouldn't be re-validated on every login.
- No blocklist of personal/free email providers, and no allowlist/override mechanism — a hard
  reject on missing MX records is the whole check.

## Architecture Decisions

**MX-record lookup via native JNDI DNS support, not a third-party library.**
`javax.naming.directory.InitialDirContext` with `com.sun.jndi.dns.DnsContextFactory` is built into
the JDK — querying the `MX` attribute for a domain needs no new Maven dependency. Rejected
alternatives: a static free/personal-provider domain blocklist (wrong tool — this app's users have
varied custom company domains, not a small enumerable set of consumer providers to block); a
disposable-email-checker library (solves a different problem — detecting throwaway providers like
10minutemail, not verifying a domain is real).

**Any DNS resolution failure is treated as "invalid domain," including transient timeouts.**
A `NamingException` from the lookup — whether NXDOMAIN or a timeout — results in rejection. This
was an explicit choice: "hard reject only, no allowlist/override" was the stated requirement, and
from this check alone a genuinely-typo'd domain and a domain with momentarily-flaky DNS are
indistinguishable. Accepted as a real (if rare) false-rejection risk rather than adding an
override mechanism that was explicitly ruled out.

**Lookup timeout is bounded and configurable, not left to JNDI's defaults.**
`com.sun.jndi.dns.timeout.initial`/`com.sun.jndi.dns.timeout.retries` are set explicitly (default
2000ms initial, 1 retry) via `EmailDomainValidationProperties`, matching the existing
`otp.*`/`trial.*` `@ConfigurationProperties` convention. Without this, a slow or unreachable DNS
server could hang a registration request for JNDI's default (much longer) timeout.

**No fallback to an A/AAAA record when MX is absent.**
RFC 5321 permits mail delivery straight to a host's A record if it has no MX record, but this is
rare in practice for a real company domain with mail properly configured — implementing the
fallback adds complexity for an edge case. Flagged as a possible future refinement if it causes
false rejections in practice.

**Lookup wrapped behind a small interface (`MxRecordLookup`), not called directly from the
validator.** The real implementation (`JndiMxRecordLookup`) is a thin `@Component`; the interface
seam lets `EmailDomainValidatorTest` exercise the pass/fail/exception-handling logic with a fake
implementation, with no real network calls in the unit test suite.

## Data Model

No schema change.

## API Surface

No new endpoints and no OpenAPI schema change — `POST /auth/register`'s existing `400` →
`ErrorResponse` response already covers this. New error code: `INVALID_EMAIL_DOMAIN`.

## Security Considerations

- This is a signup-quality check, not a security boundary — it doesn't verify the registrant
  controls the mailbox (that's what OTP verification, which already exists, does). A real MX-having
  domain with an email the registrant doesn't own would still pass this check and then fail at OTP
  verification as intended.
- Doesn't introduce any new secret or credential handling.

## Open Questions / Future Work

- **Transient DNS failures cause false rejections.** Accepted tradeoff — see Architecture
  Decisions. If this proves disruptive in practice, worth revisiting (e.g. one internal retry with
  backoff before rejecting, though that lengthens the request further).
- **No A/AAAA fallback when MX is absent.** Deliberately out of scope for now.

## Changelog

- 2026-08-24 — Implemented: `MxRecordLookup`/`JndiMxRecordLookup`, `EmailDomainValidationProperties` (`email-domain-validation.timeout-millis`/`.retries`, defaults 2000ms/1), `EmailDomainValidator`, `InvalidEmailDomainException`, wired into `UserService.registerUser` before the existing already-registered check. Unit-tested (`EmailDomainValidatorTest`, mocked `MxRecordLookup`) — pass/fail/exception-code all verified, `mvn test` green (13/13). A full app boot confirmed all three new beans and the updated `UserService` constructor wire up cleanly (Flyway validate/migrate succeeded, failure only at an unrelated, already-known local Tomcat/loopback-socket sandbox issue). The live JNDI DNS lookup itself could not be exercised in this session's sandboxed shell — it hits the same underlying `java.nio.channels.Selector` restriction as the Tomcat issue (confirmed via a standalone smoke test against `gmail.com`, which failed with `NamingException: Channel selector configuration error` even with the sandbox explicitly disabled) — this is specific to the sandbox, not expected to reproduce on a normal machine (real TCP sockets, e.g. to Postgres, already work fine there). Live verification (register with a real domain vs. a fake one) is still pending a manual run outside this sandbox.
- 2026-08-21 — Initial draft, written before implementation.
