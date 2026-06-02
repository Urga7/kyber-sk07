# Test 05 — REST API: CRUD, negotiation, TLS/HTTP2/IPv6, auth, persistence, HA, publish

Validates the FastAPI service (`dmz-app-01/app/`) and its delivery stack: PostgreSQL persistence,
content negotiation, FreeIPA TLS, HTTP/2, IPv6, LDAP-gated writes, the keepalived/nginx HA pair,
and external publishing via router DNAT + the SNI edge. Mirrors `dmz-app-01/03-rest-api.md` §6,
`04-app-02-and-ha.md` §9, `06-sni-edge-and-external-publish.md` §6, `vyos/10-dnat-publish-https.md` §5.

**Where to run:** a CA-trusting client — `kyber-ws-01` (Linux) or `kyber-ws-02` (Windows); the app
nodes for HA actions; an external box for §9. Covers **S3.1–S3.9, S3.7, I1**.

> `api.kyber.local` → VIP `192.168.7.100` / `2001:1470:fffd:99::100`. Data model: `customers`
> 1—N `orders` (FK `ON DELETE CASCADE`). Write users: `carol` ∈ `api-writers` (✓), `dave` ∉ (403).
> Credentials shown as `PASS` — substitute the real IPA password at run time.

---

## 1. Service is up; friendly root + API docs

**Run on:** client

```
curl -s https://api.kyber.local/                 # JSON welcome: message + version + "documentation":"/docs"
curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health   # 200
curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/docs     # 200 (Swagger UI)
```

**Expect:** `/` returns the welcome object (recent feature), `/health` `200`, `/docs` serves the
OpenAPI UI.

**Output:**
```
kyber@kyber-ws-01:~$ curl -s https://api.kyber.local/
{"message":"Welcome to the kyber REST API","version":"1.0","documentation":"/docs","status":"reachable"}
kyber@kyber-ws-01:~$ curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health
200
kyber@kyber-ws-01:~$ curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/docs
200
```

## 2. CRUD + the two related resources (S3.1, S3.2)

**Run on:** client

```
curl -s https://api.kyber.local/customers              # JSON array of customers
curl -s https://api.kyber.local/customers/1            # single customer (404 if id absent)
curl -s https://api.kyber.local/orders                 # JSON array of orders
curl -s https://api.kyber.local/orders/1               # single order; its customer_id links to a customer
```

**Expect:** both collections list; an order's `customer_id` references an existing customer
(the FK relationship). `GET` is public (no credentials needed).

**Output:**
```
kyber@kyber-ws-01:~$ curl -s https://api.kyber.local/customers
[{"name":"Alice Kyber","email":"alice@kyber.local","id":1,"created_at":"2026-05-25T18:11:02.266296+02:00"},{"name":"Bob Kyber","email":"bob@kyber.local","id":2,"created_at":"2026-05-25T18:11:02.266296+02:00"}]
kyber@kyber-ws-01:~$ curl -s https://api.kyber.local/customers/1
{"name":"Alice Kyber","email":"alice@kyber.local","id":1,"created_at":"2026-05-25T18:11:02.266296+02:00"}
kyber@kyber-ws-01:~$ curl -s https://api.kyber.local/orders
[{"customer_id":1,"product":"Widget","quantity":3,"amount":29.97,"id":1,"created_at":"2026-05-25T18:11:02.268365+02:00"},{"customer_id":1,"product":"Gadget","quantity":1,"amount":14.99,"id":2,"created_at":"2026-05-25T18:11:02.268365+02:00"},{"customer_id":2,"product":"Sprocket","quantity":10,"amount":99.9,"id":3,"created_at":"2026-05-25T18:11:02.268365+02:00"},{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95,"id":4,"created_at":"2026-05-30T16:23:50.652044+02:00"},{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95,"id":5,"created_at":"2026-05-30T17:19:34.077948+02:00"},{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95,"id":6,"created_at":"2026-06-01T14:56:50.600214+02:00"},{"customer_id":1,"product":"Portable car jump starter","quantity":1,"amount":120.0,"id":9,"created_at":"2026-06-02T21:03:15.810315+02:00"}]
kyber@kyber-ws-01:~$ curl -s https://api.kyber.local/orders/1
{"customer_id":1,"product":"Widget","quantity":3,"amount":29.97,"id":1,"created_at":"2026-05-25T18:11:02.268365+02:00"}
```

## 3. Content negotiation — JSON / XML / HTML (S3.3)

**Run on:** client

```
curl -s -H 'Accept: application/json' https://api.kyber.local/customers | head -c 120   # {...} / [...]
curl -s -H 'Accept: application/xml'  https://api.kyber.local/customers | head -c 120   # <customers><customer>…
curl -s -H 'Accept: text/html'        https://api.kyber.local/customers | head -c 200   # <table> … rendered
```

**Expect:** the **same** resource serialized three ways by the `Accept` header (three formats =
brief requirement). Default (no/`*/*` Accept) → JSON.

**Output:**
```
kyber@kyber-ws-01:~$ curl -s -H 'Accept: application/json' https://api.kyber.local/customers | head -c 120
[{"name":"Alice Kyber","email":"alice@kyber.local","id":1,"created_at":"2026-05-25T18:11:02.266296+02:00"},{"name":"Bob 
kyber@kyber-ws-01:~$ curl -s -H 'Accept: application/xml' https://api.kyber.local/customers | head -c 120
<?xml version="1.0" encoding="UTF-8"?><customers><customer><name>Alice Kyber</name><email>alice@kyber.local</email><id>1
kyber@kyber-ws-01:~$ curl -s -H 'Accept: text/html' https://api.kyber.local/customers | head -c 200
<!doctype html><html><head><meta charset='utf-8'><title>customers</title></head><body><h1>customers</h1><table border='1' cellpadding='4'><thead><tr><th>name</th><th>email</th><th>id</th><th>created_a
```

## 4. TLS with a real (FreeIPA-CA) certificate (S3.5)

**Run on:** client

```
openssl s_client -connect api.kyber.local:443 -servername api.kyber.local </dev/null 2>/dev/null \
  | grep -E 'subject=|issuer=|Verify return code'
```

**Expect:** subject `CN=api.kyber.local`, issuer the **KYBER.LOCAL** CA, `Verify return code: 0
(ok)` — a real CA-signed cert, not throwaway self-signed. (On an app node,
`sudo ipa-getcert list` should read `status: MONITORING`.)

**Output:**
```
kyber@kyber-ws-01:~$ openssl s_client -connect api.kyber.local:443 -servername api.kyber.local </dev/null 2>/dev/null \
  | grep -E 'subject=|issuer=|Verify return code'
subject=O=KYBER.LOCAL, CN=api.kyber.local
issuer=O=KYBER.LOCAL, CN=Certificate Authority
Verify return code: 0 (ok)
```

## 5. HTTP/2 + IPv6 (S3.6, S3.9)

**Run on:** `kyber-ws-01` (Linux — its `curl` has nghttp2; Windows `curl.exe`/Schannel may not)

```
curl -s -o /dev/null -w 'HTTP/%{http_version} %{http_code}\n' --http2 https://api.kyber.local/customers   # HTTP/2 200
curl -6 -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health                                 # 200 over IPv6
```

**Expect:** negotiated protocol `HTTP/2`; the IPv6 path (client `9a::` → VIP `99::100`) returns
`200`. (Browser DevTools → Network → Protocol column also shows `h2`.)

**Output:**
```
kyber@kyber-ws-01:~$ curl -s -o /dev/null -w 'HTTP/%{http_version} %{http_code}\n' --http2 https://api.kyber.local/customers
HTTP/2 200
kyber@kyber-ws-01:~$ curl -6 -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health
200
```

## 6. Persistence across restart (S3.4)

**Run on:** client (create a row), then an app node (restart), then client (re-read)

```
# 1. create (carol is in api-writers)
curl -u carol:PASS -X POST https://api.kyber.local/customers \
  -H 'Content-Type: application/json' -d '{"name":"Persist Test","email":"persist@kyber.local"}'   # 201, note the id
# 2. on kyber-app-01:  sudo systemctl restart kyber-api postgresql
# 3. re-read
curl -s https://api.kyber.local/customers | grep -o 'persist@kyber.local'    # still present
```

**Expect:** the created customer survives the API **and** database restart (stored in PostgreSQL,
not memory). Clean up with `DELETE /customers/{id}` (cascades to its orders).

**Output:**
```
kyber@kyber-ws-01:~$ curl -u carol:PASS -X POST https://api.kyber.local/customers \
  -H 'Content-Type: application/json' -d '{"name":"Persist Test2","email":"persist2@kyber.local"}'
{"name":"Persist Test2","email":"persist2@kyber.local","id":4,"created_at":"2026-06-02T..."}

# on kyber-app-01:  sudo systemctl restart kyber-api postgresql

kyber@kyber-ws-01:~$ curl -s https://api.kyber.local/customers | grep -o 'persist2@kyber.local'
persist2@kyber.local
```

## 7. LDAP-gated writes — public GET, protected POST/PUT/DELETE (S3.8)

**Run on:** client

```
# no credentials -> 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'   # 401
# valid IPA user but NOT in api-writers -> 403
curl -s -o /dev/null -w '%{http_code}\n' -u dave:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'   # 403
# api-writers member -> 201
curl -s -o /dev/null -w '%{http_code}\n' -u carol:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'   # 201
```

**Expect:** `401` (no auth) → `403` (authenticated, not authorized) → `201` (authorized). The gate
binds to FreeIPA over `ldaps://` and checks `api-writers` membership (`app/auth.py`).

> On Windows (`ws-02`) pass the body via `-d "@order.json"`, not inline — PowerShell strips inner
> quotes (see `ws-02/01-ca-trust-and-acceptance.md`).

**Output:**
```
kyber@kyber-ws-01:~$ # no credentials -> 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'
401
kyber@kyber-ws-01:~$ # valid IPA user but NOT in api-writers -> 403
curl -s -o /dev/null -w '%{http_code}\n' -u dave:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'
403
kyber@kyber-ws-01:~$ # api-writers member -> 201
curl -s -o /dev/null -w '%{http_code}\n' -u carol:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'
201
```

## 8. High availability — failover (S3.7)

**Run on:** an app node + client

```
# VIP sits on the MASTER
ip -br addr show ens160        # on kyber-app-01: shows 192.168.7.100 + ::100 ; on app-02: absent

# FAILOVER: take down app-01's web tier
#   on kyber-app-01:  sudo systemctl stop nginx kyber-api
#   within ~2-3s keepalived moves the VIP to app-02 (check: ip -br addr show ens160 on app-02)
curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health     # still 200 (served by app-02)
#   restore:  on kyber-app-01  sudo systemctl start kyber-api nginx   -> VIP returns to app-01

# SINGLE-BACKEND death (no VIP move): stop only kyber-api on ONE node
#   -> nginx upstream serves from the surviving uvicorn (active-active)
curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health     # 200

# writes still work through the VIP after failover
curl -s -o /dev/null -w '%{http_code}\n' -u carol:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'   # 201
```

**Expect:** killing one node's nginx+API keeps the service up (VIP migrates); killing one uvicorn
keeps it up (upstream fails over). **Database tier:** PostgreSQL is currently a single primary on
app-01 (accepted SPOF). Auto-failover via **Patroni** on the S4 etcd cluster is documented in
[`08-etcd-patroni-ha.md`](08-etcd-patroni-ha.md) but **not yet executed/verified**; once deployed it
promotes the app-02 standby and retires this SPOF.

**Output:**
```
# VIP is co-located on the MASTER (app-01); app-02 holds only its own .11 / ::11
kyber@kyber-app-01:~$ ip -br addr show ens160
ens160   UP   192.168.7.10/24 metric 100 192.168.7.100/24 2001:1470:fffd:99::100/64 2001:1470:fffd:99::10/128 fe80::20c:29ff:fea9:471/64
kyber@kyber-app-02:~$ ip -br addr show ens160
ens160   UP   192.168.7.11/24 metric 100 2001:1470:fffd:99::11/128 fe80::20c:29ff:fee3:a780/64

# FAILOVER: 'sudo systemctl stop nginx kyber-api' on app-01 -> keepalived moves the VIP to app-02
kyber@kyber-ws-01:~$ curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health
200

# SINGLE-BACKEND death: stop only kyber-api on one node -> nginx upstream serves from the survivor
kyber@kyber-ws-01:~$ curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health
200

# writes still work through the VIP after failover
kyber@kyber-ws-01:~$ curl -s -o /dev/null -w '%{http_code}\n' -u carol:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'
201
```

## 9. External publishing — DNAT + SNI edge (I1)

**Run on:** a **genuinely off-LAN, off-VPN** box (phone hotspot / cloud VM). The IPv4 check below is
from a Linux/WSL shell; the IPv6 check is from Windows `curl.exe`, because WSL has no IPv6.

```
# 1. raw reachability (was filtered before the I1 DNAT rule)
nc -vz 88.200.24.237 443                                  # open

# 2. full IPv4 request without owning a domain: --resolve supplies name->IP so curl sends the SNI
curl -v --resolve api.kyber.local:443:88.200.24.237 \
     --cacert dmz-ldap/kyber-ipa-ca.crt \
     https://api.kyber.local/health                       # 200

# 3. IPv6 goes direct to the VIP (no NAT). Run from Windows curl.exe (WSL has no IPv6);
#    add --ssl-revoke-best-effort because the FreeIPA CA's CRL is internal-only and Schannel
#    otherwise hard-fails with "schannel: the revocation status is unknown".
curl.exe -6 --ssl-revoke-best-effort \
     --resolve api.kyber.local:443:2001:1470:fffd:99::100 \
     --cacert \\wsl.localhost\FedoraLinux-44\home\luka\kyber-sk07\dmz-ldap\kyber-ipa-ca.crt \
     https://api.kyber.local/health                       # {"status":"ok"}
```

**Run on:** `kyber-rtr` — watch the translation:

```
show nat destination rules            # rule 100: 88.200.24.237:443 -> 192.168.7.100
show nat destination statistics       # counters climb on each external request
```

**Output:**
```
@luka ➜ kyber-sk07 git(main) nc -vz 88.200.24.237 443
Ncat: Version 7.92 ( https://nmap.org/ncat )
Ncat: Connected to 88.200.24.237:443.
Ncat: 0 bytes sent, 0 bytes received in 0.01 seconds.

@luka ➜ kyber-sk07 git(main) curl -v --resolve api.kyber.local:443:88.200.24.237 \
    --cacert dmz-ldap/kyber-ipa-ca.crt https://api.kyber.local/health
*   Trying 88.200.24.237:443...
* ALPN: curl offers h2,http/1.1
* SSL connection using TLSv1.3 / TLS_AES_256_GCM_SHA384 / x25519 / RSASSA-PSS
* ALPN: server accepted h2
* Server certificate:
*   subject: O=KYBER.LOCAL; CN=api.kyber.local
*   start date: May 25 18:27:37 2026 GMT
*   expire date: May 25 18:27:37 2028 GMT
*   issuer: O=KYBER.LOCAL; CN=Certificate Authority
*   subjectAltName: "api.kyber.local" matches cert's "api.kyber.local"
* SSL certificate verified via OpenSSL.
* Established connection to api.kyber.local (88.200.24.237 port 443) from 172.20.247.75 port 56682
* using HTTP/2
> GET /health HTTP/2
> Host: api.kyber.local
< HTTP/2 200
< server: nginx/1.24.0 (Ubuntu)
< content-type: application/json
< content-length: 15

# IPv6 — direct to the VIP, from Windows curl.exe
PS C:\Users\Luka> curl.exe -6 --ssl-revoke-best-effort --resolve api.kyber.local:443:2001:1470:fffd:99::100 --cacert \\wsl.localhost\FedoraLinux-44\home\luka\kyber-sk07\dmz-ldap\kyber-ipa-ca.crt https://api.kyber.local/health
{"status":"ok"}
```

> Without `--ssl-revoke-best-effort` the IPv6 call fails `curl: (60) schannel: the revocation status
> is unknown` — note this is **not** a reachability failure: the TLS handshake completed over IPv6
> to the public VIP and the cert verified; Schannel only couldn't fetch the internal-only CRL. The
> flag tells it to soft-fail that check, yielding the clean `200`.

**SNI demux (prove one public :443 fans out by hostname):** from a CA-trusting box pointed at the
VIP, different SNI names route to different backends —

```
for n in api grafana ntopng; do
  curl -sI --resolve $n.kyber.local:443:192.168.7.100 https://$n.kyber.local/ -k | head -1
done
# on the VIP-holding app node:  sudo tail /var/log/nginx/sni.log   # SNI=... -> backend, one line each
```

**Expect:** the API is publicly reachable on both stacks; the SNI edge routes `api`/`grafana`/
`ntopng` by hostname. **Dashboard exposure:** over IPv4 the edge `geo $dash_ok` keeps Grafana/ntopng
private by default; over IPv6 confirm the `WAN-DMZ6` state per [`02-firewall.md`](02-firewall.md) §4.

**Output:**
```
kyber@kyber-ws-01:~$ for n in api grafana ntopng; do
  curl -sI --resolve $n.kyber.local:443:192.168.7.100 https://$n.kyber.local/ -k | head -1
done
HTTP/2 405
HTTP/2 302
HTTP/2 302
```

> `api` → `405` (the `/` route is GET-only and `-I` sends a HEAD), `grafana`/`ntopng` → `302` (login
> redirects): three distinct backends answered the same VIP:443 by SNI, so the fan-out works.
