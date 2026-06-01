# REST API — FastAPI + nginx TLS (S3.2–S3.6, S3.8, S3.9, S3.13)

Deploys the REST API on `kyber-app-01`: a FastAPI app serving full CRUD for
`customers` and `orders` with **content negotiation** (JSON / XML / HTML), backed by
the PostgreSQL from `02-postgresql.md`, fronted by **nginx** for **TLS + HTTP/2** on
both IPv4 and IPv6, with a **FreeIPA-issued certificate**. Write endpoints are
protected against the FreeIPA `api-writers` group (S3.8).

> Scope: this stands up **instance 1**. High availability (S3.7 — a second instance on
> `sk07-app-02` behind a load-balancer VIP `192.168.7.100`) and external DNAT (I1) come
> later; here `api.kyber.local` resolves directly to app-01's `.10` / `::10`.

## 1. System packages, app user, virtualenv

```
sudo apt -y install python3-venv python3-dev nginx libpq5
sudo useradd --system --home /opt/kyber-api --shell /usr/sbin/nologin kyberapi
sudo mkdir -p /opt/kyber-api/app
sudo chown -R kyberapi:kyberapi /opt/kyber-api
```

Create the dependency manifest and the venv. The manifest is version-controlled at
[`requirements.txt`](requirements.txt) in this directory; copy it up (via the jump-host,
into `kyber`'s home, then move it into place):

```
scp -J vyos@10.7.99.1 requirements.txt kyber@192.168.7.11:~/
ssh -J vyos@10.7.99.1 kyber@192.168.7.11 \
  'sudo install -o kyberapi -g kyberapi -m 644 ~/requirements.txt /opt/kyber-api/requirements.txt'
```

Then build the venv on the box:

```
sudo -u kyberapi python3 -m venv /opt/kyber-api/venv
sudo -u kyberapi /opt/kyber-api/venv/bin/pip install --upgrade pip
sudo -u kyberapi /opt/kyber-api/venv/bin/pip install -r /opt/kyber-api/requirements.txt
```

## 2. Application source

The application lives in the version-controlled [`app/`](app/) directory beside this
runbook — edit it in an IDE (PyCharm) and deploy with [`deploy.ps1`](deploy.ps1) rather
than pasting heredocs. The tree:

```
app/
├── __init__.py            empty package marker
├── database.py            SQLAlchemy engine + session (DATABASE_URL from env)
├── models.py              Customer / Order ORM models (one-to-many, cascade)
├── schemas.py             Pydantic request/response shapes
├── serialization.py       content negotiation — JSON / XML / HTML (S3.3)
├── auth.py                FreeIPA LDAP bind + api-writers check (S3.8)
└── main.py                FastAPI routes (CRUD for customers and orders)
```

On the box these install to `/opt/kyber-api/app/`, owned by `kyberapi`. The systemd
unit (§3) runs `uvicorn app.main:app` with `WorkingDirectory=/opt/kyber-api`.

### Deploy

From this directory on the Windows machine:

```
.\deploy.ps1
```

The script copies `app/` to `~/kyber-app-staging` on app-01 via the VyOS jump-host
(login user `kyber`), then `sudo rsync`es it into `/opt/kyber-api/app`, fixes ownership
to `kyberapi`, and restarts `kyber-api`. It assumes the env file and systemd unit from
§3 already exist (first-time setup must run §3 once). Override the hosts with
`-Jump`/`-Target` if the addresses differ.

> **First deploy** must run *after* §1 (venv) but the service restart in the script
> needs the §3 unit; on a fresh box, do §1 → §3 → then `deploy.ps1`. On every
> subsequent code change, `deploy.ps1` alone is enough.
>
> The script uses `scp`/`ssh` (stock Windows OpenSSH). `sudo rsync` on the box requires
> the `kyber` user's sudo to be non-interactive for the deploy to run unattended; with a
> password-prompting sudo, run `deploy.ps1` from an interactive terminal so you can type
> it.

## 3. systemd service + environment

The DB password and LDAP settings live in a root-owned env file, **not** in the repo.
Use the same password you set in `02-postgresql.md` §2.

```
sudo tee /etc/kyber-api.env >/dev/null <<'ENV'
KYBER_DATABASE_URL=postgresql+psycopg://kyber_api:CHANGE_ME@127.0.0.1/kyber
KYBER_LDAP_URI=ldaps://kyber-ldap.kyber.local
KYBER_LDAP_BASE=dc=kyber,dc=local
KYBER_WRITER_GROUP=api-writers
KYBER_AUTH_ENABLED=1
ENV
sudo chown root:kyberapi /etc/kyber-api.env
sudo chmod 640 /etc/kyber-api.env
```

```
sudo tee /etc/systemd/system/kyber-api.service >/dev/null <<'UNIT'
[Unit]
Description=kyber REST API (FastAPI)
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
User=kyberapi
Group=kyberapi
WorkingDirectory=/opt/kyber-api
EnvironmentFile=/etc/kyber-api.env
ExecStart=/opt/kyber-api/venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now kyber-api
sudo systemctl status kyber-api --no-pager
curl -s -H 'Accept: application/json' http://127.0.0.1:8000/customers   # JSON from the app directly
```

uvicorn binds `0.0.0.0:8000` so the HA peer's nginx can reach this backend over the DMZ
(`04-app-02-and-ha.md`); it's plain HTTP but **DMZ-internal only** — WAN→DMZ permits just
`:443` and the host runs no local firewall. nginx terminates TLS and is the only WAN-facing
listener.

## 4. FreeIPA enrollment + TLS certificate (S3.5)

The brief requires **real certificates**, so issue the API cert from the FreeIPA CA via
`certmonger` (auto-renewing). First enroll the host — that also drops the IPA CA into
the system trust store (used by nginx and by `ldap3` in `auth.py`).

> **Prerequisite:** DNS must resolve `kyber.local` SRV/host records — confirm the VyOS
> forwarder repoint (`00-os-install-config.md` §5) is done.

```
sudo apt -y install freeipa-client
sudo ipa-client-install \
  --domain=kyber.local --realm=KYBER.LOCAL \
  --server=kyber-ldap.kyber.local \
  --mkhomedir --no-ntp
```

**On `kyber-ldap` (as an IPA admin)** — publish the service name and its DNS records,
and authorize app-01 to obtain the cert:

```
kinit admin
ipa dnsrecord-add kyber.local api --a-rec=192.168.7.10 --aaaa-rec=2001:1470:fffd:99::10
ipa host-add api.kyber.local --force
ipa service-add HTTP/api.kyber.local
ipa service-add-host HTTP/api.kyber.local --hosts=kyber-app-01.kyber.local
```

> `api.kyber.local` is only a DNS alias for app-01, not an enrolled host — so a host
> object must be created first with `ipa host-add … --force` (no machine enrolls as it;
> `--force` skips the DNS-resolution check). Without it, `service-add` fails with
> *"host 'api.kyber.local' does not exist to add a service to"* — and `--force` on
> `service-add` does **not** help, as it only bypasses the DNS check, not host existence.
> The `service-add-host` line then authorizes the real enrolled host
> (`kyber-app-01.kyber.local`) to manage the service and request its cert.

**Back on `kyber-app-01`** — request the cert; certmonger tracks and renews it and
reloads nginx on each renewal:

```
sudo mkdir -p /etc/ssl/kyber
sudo ipa-getcert request \
  -K HTTP/api.kyber.local \
  -N CN=api.kyber.local \
  -D api.kyber.local \
  -f /etc/ssl/kyber/api.crt \
  -k /etc/ssl/kyber/api.key \
  -C "systemctl reload nginx"
sudo ipa-getcert list      # status should reach MONITORING (= issued, tracking)
```

> `-N CN=api.kyber.local` is required: certmonger otherwise defaults the CSR subject CN
> to the machine's own hostname (`kyber-app-01`), and the IPA CA rejects it with
> *"hostname in subject … does not match name or aliases of principal
> 'HTTP/api.kyber.local'"* (error 3009). The subject CN must match the service
> principal's host. If a request is already stuck in `CA_REJECTED`, fix it with
> `ipa-getcert resubmit -i <ID> -N CN=api.kyber.local -D api.kyber.local`.

## 5. nginx — TLS, HTTP/2, dual-stack (S3.6, S3.9)

```
sudo tee /etc/nginx/sites-available/kyber-api >/dev/null <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name api.kyber.local;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name api.kyber.local;

    ssl_certificate     /etc/ssl/kyber/api.crt;
    ssl_certificate_key /etc/ssl/kyber/api.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX

sudo ln -sf /etc/nginx/sites-available/kyber-api /etc/nginx/sites-enabled/kyber-api
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

The dual `listen`/`listen [::]` pair makes the API reachable at both `192.168.7.10` and
`2001:1470:fffd:99::10`, satisfying the IPv6 requirement (S3.9).

> **HA:** with app-02, this single `proxy_pass` becomes an `upstream` across both backends
> (applied identically on both nodes), fronted by a `keepalived` VIP — see
> [`04-app-02-and-ha.md`](04-app-02-and-ha.md) §5–§7.

> nginx 1.24 (Ubuntu 24.04) uses the `listen … ssl http2;` form above. On nginx ≥1.25.1
> the preferred form is `listen 443 ssl;` + a separate `http2 on;` directive.
> HTTP/3 (S3.10, optional) would add `listen 443 quic reuseport;` + an `Alt-Svc` header.

## 6. Acceptance (S3.12)

Run from a host that trusts the IPA CA — the internal workstations are set up for exactly
this in [`ws-01/`](../ws-01/01-ca-trust-and-acceptance.md) (Linux) and
[`ws-02/`](../ws-02/01-ca-trust-and-acceptance.md) (Windows), or use any box with
`dmz-ldap/kyber-ipa-ca.crt` installed. Content negotiation:

```
curl -H 'Accept: application/json' https://api.kyber.local/customers     # JSON array
curl -H 'Accept: application/xml'  https://api.kyber.local/customers     # <customers><customer>…
curl -H 'Accept: text/html'        https://api.kyber.local/customers     # rendered table
```

Persistence — created rows survive `sudo systemctl restart kyber-api postgresql`.

HTTP/2 — confirm the negotiated protocol is `h2`:

```
curl -s -D - -o /dev/null --http2 https://api.kyber.local/customers | grep -i '^HTTP'   # HTTP/2 200  (GET headers via -D -; -I/HEAD would 405 — routes are GET-only)
```

IPv6:

```
curl -6 https://api.kyber.local/health
```

Auth (S3.8) — `carol` is in `api-writers`, `dave` is not:

```
curl -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' \
  -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'            # 401 (no auth)
curl -u dave:PASS  -X POST … same body                                          # 403 (not api-writers)
curl -u carol:PASS -X POST … same body                                          # 201 (created)
```

Document the data model, endpoints, negotiation, auth flow, and TLS/HA design in
[`README.md`](README.md) (S3.13).
