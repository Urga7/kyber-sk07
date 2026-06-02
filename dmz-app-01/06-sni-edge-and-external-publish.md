# SNI edge — one public :443, three services (I1)

Pairs with the router DNAT (`vyos/10-dnat-publish-https.md`). DNAT delivers all WAN `:443`
traffic to the HA VIP `192.168.7.100`; this runbook makes the VIP holder **demultiplex by
hostname (SNI)** so one public IP serves three TLS services:

```
                 88.200.24.237:443  (one public IPv4)
                          │  DNAT (vyos/10)
                          ▼
        VIP 192.168.7.100:443  ──  nginx stream{} ssl_preread  (app-01/app-02)
                          │  reads SNI, forwards the *encrypted* stream
        ┌─────────────────┼──────────────────────────┐
   api.kyber.local   grafana.kyber.local        ntopng.kyber.local
        │                 │                          │
  127.0.0.1:8443     192.168.7.20:443          192.168.7.20:443
  (local API nginx   (mon nginx terminates,    (mon nginx terminates,
   → upstream :8000   own FreeIPA cert)          own FreeIPA cert)
   on both nodes)
```

We use **TLS passthrough** (`ssl_preread`), not termination: the edge reads the SNI at L4 and
forwards the still-encrypted connection to the backend that already owns that name's cert. So
**no certs move**, end-to-end TLS and HTTP/2 (ALPN) are preserved, and each service keeps the
exact config it has today.

> **Why this is needed for IPv4 but not IPv6.** One public IPv4 → many services means the
> *only* thing distinguishing two `:443` connections is the **hostname in the SNI**, so
> something must demux on it. Over **IPv6** each service has its own global address
> (`api ::100`, `grafana`/`ntopng ::20`), so they're already distinct endpoints — clients go
> **direct**, no DNAT, no edge. The whole SNI dance is an IPv4-scarcity artifact.

---

> ## ⚠️ Security posture — Grafana/ntopng stay off the public internet
> `dmz-mon/03-grafana.md`, `04-ntopng-netflow.md` and `network/firewall-policy.md` keep
> Grafana and ntopng **internal-only**. We honour that: the SNI edge *can route* all three
> names (so the demux is demonstrable), but the **dashboards are gated to admin sources only**
> — the public path to them is closed by default. Enforced on both stacks:
> - **IPv4:** the `geo $dash_ok` allow-list in §3 — default-deny; the public hits a dead-end.
> - **IPv6:** the `WAN-DMZ6` narrowing in `vyos/10-dnat-publish-https.md` §2b — only the API
>   VIP `::100` is publicly reachable; `grafana`/`ntopng` on `::20` fall to default-drop.
> - Only the **API** is intentionally public on both stacks.
> - **Still change the default `admin/admin`** on Grafana and ntopng — defence in depth.
> - LAN and VPN users reach the dashboards normally (direct to mon, a different zone-pair —
>   unaffected by either gate). To demo the dashboards from one specific external box, add its
>   public IP to `geo $dash_ok` (v4) and a `WAN-DMZ6` source-scoped rule (v6).

---

## 1. DNS — names that resolve for each audience

Three audiences, three resolution paths to the **same** names:

| Audience | Resolver | api / grafana / ntopng resolve to |
|---|---|---|
| Internal LAN | FreeIPA via VyOS forwarder | private DMZ IPs (direct, no edge) — **already works** |
| VPN | pushed FreeIPA DNS | same as internal — **already works** |
| External (no domain) | the client's **hosts file** (this runbook) | public IPv4 + each service's global IPv6 |

The FreeIPA records already exist (`api`→VIP, `grafana`/`ntopng`→mon, both stacks — see the
mon runbooks §3/§6 and `04-app-02-and-ha.md`). **Nothing to add server-side.** External
client setup is §5.

## 2. Move the API vhost off :443 → loopback :8443 (BOTH app nodes)

The stream demux must own `:443`; the API's own TLS terminator moves to a loopback port. It
keeps its cert and its load-balancing upstream — only the `listen` lines change. Replace
`/etc/nginx/sites-available/kyber-api` (from `04-app-02-and-ha.md` §5) on **app-01 and
app-02**:

```
sudo tee /etc/nginx/sites-available/kyber-api >/dev/null <<'NGINX'
upstream kyber_api {
    server 192.168.7.10:8000 max_fails=2 fail_timeout=5s;
    server 192.168.7.11:8000 max_fails=2 fail_timeout=5s;
}

server {
    listen 80;
    listen [::]:80;
    server_name api.kyber.local;
    return 301 https://$host$request_uri;
}

server {
    # was 'listen 443 ssl http2' — now loopback only; the stream{} edge fronts :443
    listen 127.0.0.1:8443 ssl http2;
    listen [::1]:8443     ssl http2;
    server_name api.kyber.local;

    ssl_certificate     /etc/ssl/kyber/api.crt;
    ssl_certificate_key /etc/ssl/kyber/api.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass http://kyber_api;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX
```

> ⚠️ **Don't reload yet.** This step only *writes* the file — the API is now configured for
> `:8443` with nothing on `:443`. Reloading here takes the API down until §3 brings up the
> stream front door. Write the file, then go straight to §3 and reload **once** at its end.

> **Real client IP (optional).** With passthrough, the loopback API nginx sees the client as
> `127.0.0.1`. If you want the true source in logs/auth, add `proxy_protocol` to the loopback
> `listen` lines, set `proxy_protocol on;` in the stream server (§3), and add
> `set_real_ip_from 127.0.0.1; real_ip_header proxy_protocol;`. Not required for the demo.

## 3. The SNI demux — nginx `stream{}` (BOTH app nodes)

`stream{}` is a **top-level** block (a sibling of `http{}`), so it cannot live in
`conf.d/`/`sites-enabled/` (those are inside `http{}`).

On Ubuntu the stream module ships as a **dynamic** module — `nginx -V` shows
`--with-stream=dynamic`, so it must be installed **and loaded**, not just compiled in. Check,
and install it if missing (the package auto-adds the `load_module` line):

```
nginx -V 2>&1 | grep -o 'with-stream[a-z_=]*'      # expect: with-stream=dynamic (it exists)
ls /etc/nginx/modules-enabled/ | grep -i stream    # expect: 50-mod-stream.conf (= it's loaded)

# if the second command prints nothing, the module isn't loaded — install it:
sudo apt -y install libnginx-mod-stream            # provides stream + ssl_preread, auto-loaded
```

> A plain `systemctl restart nginx` will **not** add the module — `nginx -V` reflects build
> flags, not runtime state. After installing `libnginx-mod-stream` a **reload** is enough.

Then add the demux block and include it:

```
sudo tee /etc/nginx/stream-sni.conf >/dev/null <<'STREAM'
stream {
    # --- who may reach the admin dashboards over the PUBLIC edge (IPv4) ---
    # LAN/VPN users reach Grafana/ntopng DIRECTLY (mon .20 / ::20), never via this edge,
    # so this list is purely "external sources allowed on the public dashboard path".
    # Default 0 => Grafana/ntopng are CLOSED to the public internet. Add your admin box's
    # PUBLIC IP to demo them from outside; leave empty to keep them fully off the internet.
    geo $dash_ok {
        default          0;
        # 203.0.113.50/32  1;     # <- your admin/test box PUBLIC IPv4 (uncomment to allow)
    }

    # SNI name -> service class
    map $ssl_preread_server_name $svc {
        api.kyber.local      api;
        grafana.kyber.local  dash;
        ntopng.kyber.local   dash;
        default              api;     # bare-IP / unknown SNI -> API (cert mismatch = "use a name")
    }

    # final backend: API always public; dashboards only for allowed sources, else dead-end
    map $svc:$dash_ok $kyber_backend {
        api:0    127.0.0.1:8443;
        api:1    127.0.0.1:8443;
        dash:1   192.168.7.20:443;   # allowed admin source -> Grafana/ntopng on mon
        dash:0   127.0.0.1:1;        # everyone else -> nothing listening => connection refused
        default  127.0.0.1:8443;
    }

    log_format sni '$remote_addr [$time_local] SNI="$ssl_preread_server_name" svc=$svc ok=$dash_ok -> $kyber_backend';
    access_log /var/log/nginx/sni.log sni;

    server {
        listen 443;
        listen [::]:443;
        ssl_preread on;          # peek at the ClientHello SNI WITHOUT decrypting
        proxy_pass $kyber_backend;
    }
}
STREAM

# include it at the TOP level of nginx.conf (appended after the http{} block).
# GUARDED: a bare `tee -a` appends EVERY run -> duplicate 'stream' directive -> broken config.
# This adds the line only if it isn't already there, so it's safe to re-run.
grep -qF 'stream-sni.conf' /etc/nginx/nginx.conf \
  || echo 'include /etc/nginx/stream-sni.conf;' | sudo tee -a /etc/nginx/nginx.conf

sudo nginx -t && sudo systemctl reload nginx

# VERIFY the front door is actually up — BOTH :443 (stream) and :8443 (API) must listen.
# If :443 is missing, the stream block didn't load (include skipped or module not loaded).
sudo ss -ltnp | grep -E ':443|:8443'
grep -c stream-sni /etc/nginx/nginx.conf      # must be exactly 1
```

> Run this on **both** nodes. The two checks above (`:443` present, include count `= 1`) are
> the difference between "working" and the silent outage where the API moved to `:8443` but
> nothing fronts `:443`.

`ssl_preread on` reads the SNI from the **unencrypted** ClientHello (the hostname is sent in
clear, before the handshake completes), so the edge can pick a backend without holding any
private key. `listen 443` is wildcard, so whichever node holds the keepalived VIP answers on
it — HA is preserved.

> Grafana/ntopng over this path see the connection from the **app-node** IP, not the real
> client (L4 forward). Fine for the lab; their own nginx still terminates TLS with its own
> cert.

## 4. Verify the demux locally (both nodes / from the VIP holder)

```
# API still served, now via the edge (note the SNI we send with --resolve)
curl -s -o /dev/null -w '%{http_code}\n' --cacert /etc/ssl/kyber/api.crt \
     --resolve api.kyber.local:443:192.168.7.100 https://api.kyber.local/health      # 200

# grafana/ntopng fan-out through the edge VIP
curl -sI --resolve grafana.kyber.local:443:192.168.7.100 https://grafana.kyber.local/ -k | head -1
curl -sI --resolve ntopng.kyber.local:443:192.168.7.100 https://ntopng.kyber.local/  -k | head -1

sudo tail /var/log/nginx/sni.log     # each line shows SNI -> chosen backend
```

## 5. External client setup — names without a domain

On the **external test machine** (off-LAN, off-VPN). This is the piece that answers "how do I
reach them if I can't use domain names" — you *do* use the names; you just resolve them
locally.

**Linux / macOS** — `/etc/hosts`:
```
# IPv4: all three share the one public IP; SNI tells the edge which is which
88.200.24.237           api.kyber.local grafana.kyber.local ntopng.kyber.local
# IPv6: each service is its own global endpoint — direct, no edge, no NAT
2001:1470:fffd:99::100  api.kyber.local
2001:1470:fffd:99::20   grafana.kyber.local
2001:1470:fffd:99::20   ntopng.kyber.local
```

**Windows** — same lines in `C:\Windows\System32\drivers\etc\hosts` (edit as Administrator).

**Trust the CA** so the FreeIPA-issued certs validate (else the browser warns / curl needs
`-k`): import `dmz-ldap/kyber-ipa-ca.crt` — system store + browser (NSS) store, exactly as
`ws-01/01-ca-trust-and-acceptance.md` §1 describes.

Now just open `https://grafana.kyber.local` in the browser — it sends `SNI=grafana.kyber.local`,
the edge routes to mon, the cert matches, no warning. **Typing `https://88.200.24.237`
instead sends no usable SNI → you hit the `default` backend and get a cert mismatch.** That's
the whole reason you must use the name.

## 6. Acceptance (I1, both stacks)

From a **public** machine (no VPN), after §5 — the dashboards must be **closed**:

```
# IPv4 — API is public; dashboards hit the dead-end (gated off the public internet)
curl -s  https://api.kyber.local/health         # 200            (API public)
curl -sI https://grafana.kyber.local/           # connection refused / reset  (CLOSED)
curl -sI https://ntopng.kyber.local/             # connection refused / reset  (CLOSED)

# IPv6 — API VIP public; dashboard address ::20 dropped at WAN-DMZ6
curl -6 -s  https://api.kyber.local/health       # 200            (API public)
curl -6 -sI https://grafana.kyber.local/         # times out      (firewall drop)
```

From a **LAN or VPN** client the dashboards work normally (direct to mon, unaffected by the
gates):

```
curl -sI https://grafana.kyber.local/ | head -1  # 200/302  (Grafana login)
curl -sI https://ntopng.kyber.local/  | head -1  # 200/302  (ntopng login)
```

To **prove the SNI demux itself** (independent of the public gate), point a trusted client at
the VIP and watch the log — same IP, different name routes to a different backend:

```
for n in api grafana ntopng; do
  curl -sI --resolve $n.kyber.local:443:192.168.7.100 https://$n.kyber.local/ -k | head -1
done
sudo tail /var/log/nginx/sni.log    # SNI=... svc=... -> backend, one line per request
```

- Browser dev tools show protocol **h2** (ALPN survives passthrough).
- Kill nginx on the VIP-holding node → keepalived migrates the VIP, the surviving node's
  `stream{}` keeps demuxing → all three names still resolve and serve (HA intact).
- `vyos/10` §5 covers the router-side DNAT counters.

## 7. Rollback

```
# app nodes: drop the edge, restore API on :443
sudo sed -i '/stream-sni.conf/d' /etc/nginx/nginx.conf
sudo rm -f /etc/nginx/stream-sni.conf
# re-apply the 04-app-02-and-ha.md §5 kyber-api vhost (listen 443 ssl http2)
sudo nginx -t && sudo systemctl reload nginx
# router: delete nat destination rule 100, commit/save, re-run vyos/update-snapshot.sh
```
