# Internal employee SSO

## Purpose and access

Employees enter an `@sailssoftware.com` address and authenticate in Sails' Microsoft Entra tenant. Only directory members qualify; guests and other domains are rejected. The entered address is a login hint, never proof of identity. Graph supplies the authenticated user's profile after token validation. External registration and OTP remain available for other domains.

| Capability | External user | Internal employee | Admin |
| --- | --- | --- | --- |
| Browse public catalog | Yes | Yes | Yes |
| Launch published POCs | Active trial | No trial | No trial |
| Host a POC guide | Signed in | Yes | Yes |
| Deploy a POC guide | No | Yes | Yes |
| Create/edit/delete/deploy POCs | No | No | Yes |
| Customer/activity administration | No | No | Yes |
| Grant/revoke admin roles | No | No | SUPERADMIN only |

Employee classification is independent of roles. Microsoft login never grants ADMIN. Existing linked accounts retain their identifiers, roles, preferences, files, and activity.

## Protocol and interfaces

1. The portal generates a PKCE verifier and S256 challenge. `POST /auth/microsoft/start` accepts `{email, codeChallenge}` and returns `{authorizationUrl, state}`. It stores a random state hash, nonce, challenge, and ten-minute expiry in PostgreSQL. The portal stores state/verifier and a validated local return destination in session storage.
2. Entra redirects to `/auth/microsoft/callback` in the portal. The portal validates browser state, clears query parameters, and sends `{code, state, codeVerifier}` to `POST /auth/microsoft/complete`.
3. The API consumes the transaction atomically, redeems the code server-side using the client secret, and validates signature, issuer, audience, expiry, nonce, and tenant. Graph `/me` must return a Member whose object ID matches the validated token and whose mail (UPN fallback only when absent) has the exact company domain.
4. Provision/link the employee, then return the existing `LoginResponse`. Microsoft tokens and the client secret never go to the portal. Portal and POC tokens continue using the existing issuer and refresh lifecycle.

Authorization transactions are single use, including failed completion attempts after a matching verifier. Expired rows are cleaned up during new starts. No access/refresh tokens appear in redirect URLs or logs. HTTP responses containing sign-in data use `Cache-Control: no-store`. Directory/network failure ends the login without partial provisioning.

## Persistence and compatibility

An additive Flyway migration introduces `users.account_type` (EXTERNAL by default), Microsoft tenant/object identifiers with a unique composite constraint, and authorization transactions. No account is classified as internal merely because of its stored email. Case-insensitive directory-email matches must be unambiguous. Conflicting identity links and locally INACTIVE/SUSPENDED accounts fail closed. Provisioning serializes identity/email matches across API instances.

New employees receive USER and company name `Sails Software Solutions`. Names come from Graph; missing given name falls back to display name, then `Employee`; missing surname is empty. Optional profile fields remain null when absent. Names are bounded to the existing column limits. Directory fields refresh at login; existing display-name preferences are preserved.

On first transition, revoke existing portal refresh tokens, clear trial dates and extension requests, and activate pending accounts only after successful Microsoft authentication. Reject company-domain legacy registration, OTP, registration-link verification, and development token issuance. Linked internal identities remain SSO-only even if their email changes. User/customer responses and portal JWTs expose `accountType`; POC tokens retain their restricted claims and omit employee trial expiry.

Internal users cannot receive trial extensions/revocations. Trial cards, trial tour steps, trial alerts, and trial management actions exclude employees. Administrative role checks remain enforced on the API.

## Configuration and rollout

The integration defaults disabled via `MICROSOFT_SSO_ENABLED=false`. Enable after configuring:

- `MICROSOFT_TENANT_ID`, `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`.
- `MICROSOFT_REDIRECT_URI`: locally `http://localhost:4200/auth/microsoft/callback`; production uses the deployed portal origin and the same path.
- Existing local secret keys `tenantID`, `ClientID`, and `Value/secret` are fallback mappings. `SecretID` is metadata and is not used for authentication.
- Register the callback as a **Web** redirect in Entra because the backend redeems the code. Use a single-tenant app and delegated Graph `User.Read` with consent allowed or granted. Requested scopes are `openid profile email https://graph.microsoft.com/User.Read`; no directory-wide access or Microsoft refresh tokens are required.

Deploy the additive API/schema before the portal, then configure Entra and enable SSO. Disabling SSO stops new Microsoft logins; it does not reopen employee OTP access. Keep an existing nonemployee administrator available during setup. Existing access tokens can remain valid until their expiry after account linking.

As agreed, no background directory-offboarding checks are added. A disabled Microsoft account is rejected at the next Microsoft sign-in. Portal refresh tokens retain their existing rotating/sliding lifetime, so active sessions can continue while renewed. Local suspended/inactive accounts cannot obtain new sessions or refresh tokens.

## Validation

Automated tests use mocked Microsoft/Graph responses and locally generated signing keys. Cover new/repeated provisioning, history/admin preservation, identity collisions, concurrent completion, missing fields, wrong tenants/domains/guests, invalid signature/audience/expiry/nonce/PKCE, expired/replayed state, Graph outages, safe destinations, legacy bypasses, trial-free portal/POC access, and admin denials. Run the API tests, portal tests, and production portal build. Live credential, consent, callback, and Microsoft sign-in tests are deferred until implementation is complete.

## Future Asset Hub (not implemented in this release)

**Problem:** AI use cases, POCs, blogs, articles, hackathon ideas, and documents are scattered across SharePoint and repositories, slowing discovery and reuse and obscuring ownership/review status.

**Solution and journeys:** Employees submit assets or source links, AI suggests metadata, reviewers approve submissions, and colleagues discover and reuse approved assets. Track owners, types, tags, review status, source links, and feedback.

**Initial POC scope:** Employee-only catalog, manual submissions, one selected SharePoint collection, review workflow, keyword/semantic search, and original-source links. Preserve source permissions during ingestion and retrieval.

**Architecture and AI:** Reuse verified employee identity and backend access controls; add asset/review APIs, metadata storage, ingestion workers, and a semantic index. Extract metadata, suggest summaries/tags, and retrieve relevant assets with permission filtering. Protect APIs, stored files, and search results, not just navigation.

**Success metrics:** Time to find a useful asset, successful-search rate, reuse activity, contributor adoption, and review turnaround time.

Reference: [Microsoft authorization code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow).
