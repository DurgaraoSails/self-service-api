# Email Templates

## Status

Implemented

## Overview / Purpose

Building on `docs/specs/transactional-email.md` (which shipped a real SMTP-backed `EmailService`
sending plain-text mail), this feature adds branded HTML templates so transactional emails — starting
with the OTP verification code — look like they come from Sails Software rather than a raw text
string. `EmailService` previously had a single `send(to, subject, body)` method built for plain text
only; it couldn't express "render this HTML template with these variables," so branding wasn't
possible without extending the interface.

## Requirements

- OTP emails (registration + login — the only email an external prospect ever sees) must be sent as
  branded HTML: Sails Software logo, brand color, dynamic OTP code/expiry, without breaking in
  mainstream email clients (Gmail, Outlook).
- Contact Sales notification (internal, to the sales team) gets the same treatment for consistency,
  since the infrastructure ends up costing almost nothing extra once OTP templating exists.
- The existing plain-text `send()` path keeps working unchanged — nothing currently depends on
  removing it.
- Local dev (`local` profile) must not require real SMTP just to see what a templated email would
  contain.
- The logo image must be genuinely part of the shipped artifact (packaged on the classpath), not
  something that only happens to work from an IDE's dev classpath.

## Architecture Decisions

**Thymeleaf (`spring-boot-starter-thymeleaf`), not a raw string-templating approach.** Standard
Spring Boot integration; the auto-configured `SpringTemplateEngine`/`SpringStandardDialect`
combination is what backs `sendTemplate` in production. (Note for anyone writing a similar
standalone preview/test harness: instantiating a bare `org.thymeleaf.TemplateEngine` outside Spring
pulls in the OGNL-based `StandardDialect`, which needs the `ognl` jar — not on this project's
classpath. Use `org.thymeleaf.spring6.SpringTemplateEngine` instead, which uses SpringEL and needs
nothing extra.)

**`EmailService` gained a second method, `sendTemplate(to, subject, templateName, Map<String,Object>
variables)`, rather than replacing `send`.** Keeps the plain-text path available (nothing currently
needs it, but no reason to force every caller through HTML rendering) and keeps the change
additive/non-breaking.

**`SmtpEmailService.sendTemplate` sends real multipart MIME (`MimeMessageHelper`,
`MULTIPART_MODE_MIXED_RELATED`), not `SimpleMailMessage`.** `SimpleMailMessage` (used by the existing
`send()`) cannot carry HTML or attachments at all. Multipart mode is needed for two things at once:
an HTML part with a plain-text alternative (`helper.setText(plainText, html)` — needed for
deliverability/spam scoring and text-only clients), and an inline image (the logo) attached via
Content-ID rather than a hosted URL.

**Logo is embedded inline via `Content-ID` (`helper.addInline(...)`), never a hosted URL.** This app
has no image hosting/CDN, and remote-hosted images are blocked by default in most clients until the
user clicks "show images" anyway — inline CID embedding renders immediately with no such prompt.

**Plain-text fallback is derived from the rendered HTML via a small regex-based stripper
(`SmtpEmailService.toPlainText`), not hand-written per template.** Avoids requiring template authors
to maintain two versions of every email. Explicitly not a general-purpose HTML sanitizer — good
enough for the fallback's actual purpose (deliverability + text-only clients), not for untrusted
input (there is none here; template output is always this app's own markup).

**`LoggingEmailService.sendTemplate` logs the template name + variables, not rendered output.** For
local dev, what's actually useful is seeing the raw OTP code and knowing which template would have
been used — rendering full HTML into logs would be noisy and provide no value without a browser to
view it in.

**Templates live at `src/main/resources/templates/email/*.html`, referenced by name without the
`.html` suffix or `email/` prefix from calling code** (e.g. `sendTemplate(..., "otp-verification",
...)` — the `email/` prefix is added internally in `SmtpEmailService`). Matches Spring Boot's default
Thymeleaf resolver (`classpath:/templates/`, `.html` suffix), so no custom resolver configuration was
needed for the real (non-preview) path.

**Brand values pulled from the actual frontend, not invented.** Color (`#0F766E` / teal-700,
`#115E59` / teal-800 hover, `#F0FDFA` / teal-50) taken directly from
`frontend/src/app/components/dashboard/dashboard.html`'s Tailwind classes, so the email visually
matches the product rather than being a guess. The logo is the actual Sails Software sail icon
(provided directly), not the placeholder teal-diamond badge used in the frontend header mock.

**Layout is table-based with inline styles, not modern CSS/flexbox.** Outlook desktop renders email
HTML with Word's rendering engine, which has very limited CSS support — table-based layout is the
only approach that reliably works across Gmail, Outlook, and mobile clients simultaneously.

**A 6px solid accent bar (`#0F766E`) sits at the top of the card**, above the header, on both
templates — a deliberate brand-reinforcement touch, added after review found the plain white header
looked unbranded on its own.

**Logo size (64×32, rendered from a 318×159 source) chosen after visual review, not a fixed rule.**
An earlier attempt at 34×17 was reviewed and rejected as illegible next to 18px bold wordmark text.

## Data Model

None. No new tables/entities — purely an email-rendering change.

## API Surface

No REST API changes. Internal-only change: `EmailService.sendTemplate(...)` is a new method on an
existing interface, consumed by `OtpService.sendOtpEmail` (previously called `send(...)` with a
hardcoded string) and `SupportService.contactSales` (same).

## Security Considerations

- No new secrets or auth boundary. Uses the same SMTP credentials (`secrets/smtp.properties`) as the
  existing plain-text sending path.
- The regex-based `toPlainText` HTML stripper is not a general-purpose sanitizer — safe here because
  template output is always this app's own controlled markup, never user-supplied HTML. Thymeleaf's
  `th:text` auto-escapes variable output, so user-supplied values (e.g. the Contact Sales message)
  can't inject markup into the rendered HTML in the first place.
- **Known unresolved issue, not introduced by this feature but newly relevant to it:**
  `app.mail.from-address` defaults to the placeholder `no-reply@example.com`, while the actual
  authenticated SMTP account (`secrets/smtp.properties`) is a real Gmail address. Gmail SMTP
  generally rejects or rewrites a `From:` header that doesn't match the authenticated account
  (absent a verified "Send As" alias), so mail sent with current defaults may not show the intended
  sender. Similarly, `support.sales-email` still defaults to the placeholder `sales@example.com` — no
  real inbox receives Contact Sales mail until that's set. Flagged for whoever owns SMTP/ops
  configuration; not blocking template correctness.

## Open Questions / Future Work

- **From-address / sales-email placeholder mismatch** (see Security Considerations) — needs a real
  decision, not a code fix.
- **Preview tooling.** Previews during development were generated via a throwaway JUnit test
  (`TemplatePreviewGenerator`, deleted after each use) that renders both templates with sample data +
  the logo inlined as a `data:` URI, writes to `target/email-previews/*.html`, and opens them in a
  browser. Not kept as permanent tooling — if templates change often, worth deciding whether to keep
  a version of this checked in (e.g. a `@Disabled` test or a small dev-only script) rather than
  recreating it by hand each time.
- **Only OTP and Contact Sales are templated.** No other transactional email exists in the app yet
  (trial-expiry was explicitly ruled out in `docs/specs/trial-access-enforcement.md`; a standalone
  email-verification-link flow was never built — OTP covers that role instead).

## Changelog

- 2026-08-24 — Implemented: `spring-boot-starter-thymeleaf` added; `EmailService.sendTemplate` added;
  `SmtpEmailService` rewritten to send real multipart HTML + plain-text mail via `MimeMessageHelper`
  with the logo inlined by Content-ID; `LoggingEmailService.sendTemplate` added for local dev;
  `templates/email/otp-verification.html` and `templates/email/contact-sales.html` created;
  `OtpService.sendOtpEmail` and `SupportService.contactSales` switched from `send` to `sendTemplate`;
  logo moved from a non-packaged `src/main/java/.../email/assets/` location to
  `src/main/resources/templates/email/sails-logo.png`. Iterated on logo size (34×17 → 64×32) and
  added a top accent bar after visual review. Unit tests updated (`SmtpEmailServiceTest`,
  `SupportServiceTest`) — all pass; full `mvn test-compile` clean.
