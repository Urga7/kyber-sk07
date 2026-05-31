# REST API + database VM (`sk07-app-01`) — Track S

Application VM 1 for `kyber.local`, host `kyber-app-01.kyber.local`, DMZ segment,
`192.168.7.10` / `2001:1470:fffd:99::10` (dual-stack, addresses via DHCP reservation).

Roles documented here: **PostgreSQL primary** (S3.1) and **REST API instance 1**
(S3.2–S3.6, S3.8, S3.9). Future roles on this host (etcd node 1 — S4; nginx as part of
the HA pair — S3.7) are documented in their own runbooks/READMEs.

Build order — the numbered runbooks in this directory:

| # | Runbook | Covers |
|---|---|---|
| 00 | `00-os-install-config.md` | Ubuntu Server install, DHCP reservation, dual-stack, NTP |
| 01 | `01-enable-ssh-through-rtr.md` | SSH key access via the VyOS jump-host |
| 02 | `02-postgresql.md` | PostgreSQL + `customers`/`orders` schema |
| 03 | `03-rest-api.md` | FastAPI app, FreeIPA TLS cert, systemd, nginx |
| 04 | `04-app-02-and-ha.md` | Second instance (app-02) + keepalived VIP + nginx active-active LB (S3.7) |

## Data model (S3.1)

Two related resources, one-to-many:

```
customers (id PK, name, email UNIQUE, created_at)
   │ 1
   │
   │ N
orders    (id PK, customer_id FK→customers.id ON DELETE CASCADE,
           product, quantity, amount, created_at)
```

PostgreSQL listens on loopback only; the API connects as role `kyber_api` to database
`kyber` over `127.0.0.1`.

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/customers`, `/customers/{id}` | public | list / fetch |
| POST | `/customers` | `api-writers` | 201; 409 on duplicate email |
| PUT | `/customers/{id}` | `api-writers` | replace |
| DELETE | `/customers/{id}` | `api-writers` | 204; cascades to orders |
| GET | `/orders`, `/orders/{id}` | public | list / fetch |
| POST | `/orders` | `api-writers` | 201; 400 if `customer_id` unknown |
| PUT | `/orders/{id}` | `api-writers` | replace |
| DELETE | `/orders/{id}` | `api-writers` | 204 |
| GET | `/health` | public | liveness |

## Content negotiation (S3.3)

The same resource is serialized by the request `Accept` header:

| `Accept` | Response |
|---|---|
| `application/json` (default) | JSON |
| `application/xml` / `text/xml` | XML (`<customers><customer>…`) |
| `text/html` | HTML table |

## Authentication (S3.8)

Write operations (`POST`/`PUT`/`DELETE`) require **HTTP Basic** credentials that bind
successfully to FreeIPA **and** belong to the `api-writers` group; `GET` is public.
The API binds to `ldaps://kyber-ldap.kyber.local`, trusting the IPA CA via the system
trust store (populated by `ipa-client-install`). Reference users (from `dmz-ldap/`):
`carol` ∈ api-writers (allowed), `dave` ∉ (403). Toggle with `KYBER_AUTH_ENABLED`.

## TLS / HTTP/2 / IPv6 (S3.5, S3.6, S3.9)

nginx terminates TLS and reverse-proxies to uvicorn on `127.0.0.1:8000`. The
certificate for `api.kyber.local` is issued by the **FreeIPA CA** via `certmonger`
(auto-renewing, reloads nginx on renewal) — a real CA-signed cert, not throwaway
self-signed. nginx listens on `443 ssl http2` on both IPv4 and IPv6.

## High availability (S3.7 — see `04-app-02-and-ha.md`)

A second instance runs on `kyber-app-02` (`.11` / `::11`) behind a `keepalived` virtual IP
**`192.168.7.100` / `2001:1470:fffd:99::100`**, which `api.kyber.local` resolves to. The
VIP-holder's nginx terminates TLS and load-balances **active-active** across both uvicorn
backends (`upstream`); killing one instance keeps the service up (S3.12). Both API instances
share a **single PostgreSQL primary on app-01** (no replica — an accepted SPOF for now). This
is internal/DMZ-only at present; the external DNAT (I1, `88.200.24.237:443` → VIP) is deferred.

## Config provenance

Live config is on the box (`/opt/kyber-api/`, `/etc/kyber-api.env`,
`/etc/systemd/system/kyber-api.service`, `/etc/nginx/sites-available/kyber-api`). The
application source of truth is the version-controlled [`app/`](app/) directory; edit it
in an IDE and push with [`deploy.ps1`](deploy.ps1) (copies via the jump-host, installs to
`/opt/kyber-api/app`, restarts `kyber-api`). The dependency manifest lives at
[`requirements.txt`](requirements.txt). Host/service config (env file, systemd unit,
nginx) remains documented as commands in `03-rest-api.md`. Secrets (DB password) live
only in `/etc/kyber-api.env`, never in the repo.
