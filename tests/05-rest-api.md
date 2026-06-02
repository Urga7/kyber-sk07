# Test 05 — REST API: CRUD, negotiation, TLS/HTTP2/IPv6, auth, persistence, HA, publish

Validates the FastAPI service (`dmz-app-01/app/`) and its delivery stack: PostgreSQL persistence,
content negotiation, FreeIPA TLS, HTTP/2, IPv6, LDAP-gated writes, the keepalived/nginx HA pair,
and external publishing via router DNAT + the SNI edge. Mirrors `dmz-app-01/03-rest-api.md` §6,
`04-app-02-and-ha.md` §9, `06-sni-edge-and-external-publish.md` §6, `vyos/10-dnat-publish-https.md` §5.

**Where to run:** a CA-trusting client — `kyber-ws-01` (Linux) or `kyber-ws-02` (Windows); the app
nodes for HA actions; an external box for §9. Covers **S3.1–S3.9, S3.7, I1**.

> `api.kyber.local` → VIP `192.168.7.100` / `2001:1470:fffd:99::100`. Data model: `customers`
> 1—N `orders` (FK `ON DELETE CASCADE`). Write users: `carol` ∈ `api-writers` (✓), `dave` ∉ (403).

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

## 3. Content negotiation — JSON / XML / HTML (S3.3)

**Run on:** client

```
curl -s -H 'Accept: application/json' https://api.kyber.local/customers | head -c 120   # {...} / [...]
curl -s -H 'Accept: application/xml'  https://api.kyber.local/customers | head -c 120   # <customers><customer>…
curl -s -H 'Accept: text/html'        https://api.kyber.local/customers | head -c 200   # <table> … rendered
```

**Expect:** the **same** resource serialized three ways by the `Accept` header (three formats =
brief requirement). Default (no/`*/*` Accept) → JSON.

## 4. TLS with a real (FreeIPA-CA) certificate (S3.5)

**Run on:** client

```
openssl s_client -connect api.kyber.local:443 -servername api.kyber.local </dev/null 2>/dev/null \
  | grep -E 'subject=|issuer=|Verify return code'
```

**Expect:** subject `CN=api.kyber.local`, issuer the **KYBER.LOCAL** CA, `Verify return code: 0
(ok)` — a real CA-signed cert, not throwaway self-signed. (On an app node,
`sudo ipa-getcert list` should read `status: MONITORING`.)

## 5. HTTP/2 + IPv6 (S3.6, S3.9)

**Run on:** `kyber-ws-01` (Linux — its `curl` has nghttp2; Windows `curl.exe`/Schannel may not)

```
curl -s -o /dev/null -w 'HTTP/%{http_version} %{http_code}\n' --http2 https://api.kyber.local/customers   # HTTP/2 200
curl -6 -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health                                 # 200 over IPv6
```

**Expect:** negotiated protocol `HTTP/2`; the IPv6 path (client `9a::` → VIP `99::100`) returns
`200`. (Browser DevTools → Network → Protocol column also shows `h2`.)

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
keeps it up (upstream fails over). The **database tier** is also HA now — Patroni provides PostgreSQL
auto-failover on the etcd cluster, so losing app-01 promotes the app-02 standby
(tested in [`08-etcd-patroni-ha.md`](08-etcd-patroni-ha.md) §7).

## 9. External publishing — DNAT + SNI edge (I1)

**Run on:** a **genuinely off-LAN, off-VPN** box (phone hotspot / cloud VM).

```
# 1. raw reachability (was filtered before the I1 DNAT rule)
nc -vz 88.200.24.237 443                                  # open

# 2. full request without owning a domain: --resolve supplies name->IP so curl sends the SNI
curl -v --resolve api.kyber.local:443:88.200.24.237 \
     --cacert dmz-ldap/kyber-ipa-ca.crt \
     https://api.kyber.local/health                       # 200

# 3. IPv6 goes direct to the VIP (no NAT)
curl -6 --resolve api.kyber.local:443:2001:1470:fffd:99::100 \
     --cacert dmz-ldap/kyber-ipa-ca.crt \
     https://api.kyber.local/health                       # 200
```

**Run on:** `kyber-rtr` — watch the translation:

```
show nat destination rules            # rule 100: 88.200.24.237:443 -> 192.168.7.100
show nat destination statistics       # counters climb on each external request
```

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
