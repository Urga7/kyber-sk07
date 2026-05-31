# REST API high availability — app-02 + load balancer (S3.7, S3.12)

Adds the **second API instance** on `kyber-app-02` and makes `api.kyber.local`
**highly available** behind a `keepalived` virtual IP + nginx L7 load balancing.

**Topology (as built):**
```
        api.kyber.local → VIP 192.168.7.100 / 2001:1470:fffd:99::100   (keepalived; MASTER = app-01)
                              │  TLS terminates on the VIP-holder's nginx
   nginx upstream  ┌──────────┴──────────┐
                   ▼                      ▼
   app-01 uvicorn 0.0.0.0:8000       app-02 uvicorn 0.0.0.0:8000
   (192.168.7.10/::10)               (192.168.7.11/::11)
         │ both APIs → app-01 PostgreSQL (shared single primary)
         ▼
   PostgreSQL primary on app-01 only          ← accepted SPOF (no replica, by decision)
```

**Design decisions:** shared single PostgreSQL primary on app-01 (no replica); **active-active**
load balancing (nginx `upstream` across both uvicorns); **LAN/DMZ-only** — the public DNAT (I1)
is deferred. All HA traffic (VRRP, nginx→peer `:8000`, app-02→app-01 `:5432`) is **intra-DMZ /
L2** and never traverses `kyber-rtr`, so **no new router firewall rules** are required
(`network/firewall-policy.md`: DMZ→DMZ is switch-local; WAN→DMZ and INTERNAL→DMZ `:443` already
reach the VIP).

> The `.11` / `::11` addresses, the VIP `192.168.7.100`, and app-02's DNS/PTR records were
> pre-provisioned in `dmz-ldap/05-freeipa-postinstall.md`. The `HTTP/api.kyber.local` service +
> host object already exist (`dmz-app-01/03-rest-api.md` §4).

---

## 1. Reserve app-02's addresses on the router (N3.2 / N3.4)

Create the VM on port group `sk07-dmz`, then read its NIC MAC (ESXi → Network adapter →
Advanced → MAC, or `ip link show ens160` after first boot). On `kyber-rtr` (substitute the real
MAC for `00:0C:29:E3:A7:80`). These lines also belong in `vyos/05-dhcp-internal-dmz.md` — add them
there so the runbook stays the source of truth:

```
configure
set service dhcp-server   shared-network-name DMZ  subnet 192.168.7.0/24        static-mapping app-02 ip-address '192.168.7.11'
set service dhcp-server   shared-network-name DMZ  subnet 192.168.7.0/24        static-mapping app-02 mac-address '00:0C:29:E3:A7:80'
set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping app-02 ipv6-address '2001:1470:fffd:99::11'
set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping app-02 identifier '00:03:00:01:00:0C:29:E3:A7:80'
commit
save
exit
# from your workstation:
scp vyos@88.200.24.237:/config/config.boot vyos/snapshot-config.boot
```

## 2. Build app-02 (mirror app-01)

Follow `00-os-install-config.md` and `01-enable-ssh-through-rtr.md` with these **deltas**:

| Item | Value |
|---|---|
| Hostname (installer) | `kyber-app-02` |
| Addresses | `192.168.7.11` / `2001:1470:fffd:99::11` (via the §1 reservation) |
| DUID-LL networkd step | **required** — DMZ6 has no dynamic pool (same `DUIDType=link-layer` as app-01) |
| NTP | `192.168.7.1` |

Then FreeIPA-enroll it (FQDN hostname first — the `dmz-mon/03` §3 lesson):

```
sudo hostnamectl set-hostname kyber-app-02.kyber.local
hostname -f                                   # -> kyber-app-02.kyber.local
sudo apt -y install freeipa-client
sudo ipa-client-install \
  --domain=kyber.local --realm=KYBER.LOCAL \
  --server=kyber-ldap.kyber.local \
  --mkhomedir --no-ntp
```

Sanity: `getent ahosts kyber-app-01.kyber.local` returns both families (app-02 will reach
app-01's DB and uvicorn by these).

## 3. Open app-01's PostgreSQL to app-02 (shared primary)

**On `kyber-app-01`** — see the new section in `02-postgresql.md` §6. Summary: have PostgreSQL
listen on the DMZ address and authorize only app-02's `kyber_api` connections over TLS:

```
# /etc/postgresql/16/main/postgresql.conf
listen_addresses = 'localhost,192.168.7.10,2001:1470:fffd:99::10'
# /etc/postgresql/16/main/pg_hba.conf  (append)
hostssl  kyber  kyber_api  192.168.7.11/32              scram-sha-256
hostssl  kyber  kyber_api  2001:1470:fffd:99::11/128    scram-sha-256
```
```
sudo systemctl restart postgresql
# from app-02, confirm reachability (once §4 installs libpq/psql or via the app):
PGPASSWORD=<PW> psql 'host=192.168.7.10 dbname=kyber user=kyber_api sslmode=require' -c '\dt'
```
app-01's own API keeps using `127.0.0.1` (unchanged). app-02's API uses `192.168.7.10` (§4).

## 4. Deploy the API to app-02 (active-active backend)

On app-02, mirror `03-rest-api.md` §1–§3 (the `kyberapi` user, venv from `requirements.txt`,
`/etc/kyber-api.env`, the `kyber-api.service` unit). Two differences:

- **env delta** — point the DB at app-01's primary:
  ```
  KYBER_DATABASE_URL=postgresql+psycopg://kyber_api:<PW>@192.168.7.10/kyber
  KYBER_LDAP_URI=ldaps://kyber-ldap.kyber.local
  KYBER_LDAP_BASE=dc=kyber,dc=local
  KYBER_WRITER_GROUP=api-writers
  KYBER_AUTH_ENABLED=1
  ```
- **uvicorn bind (BOTH nodes)** — the peer's nginx must reach this backend over the DMZ, so the
  `ExecStart` in `kyber-api.service` binds all interfaces instead of loopback:
  ```
  ExecStart=/opt/kyber-api/venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
  ```
  Apply on app-01 too (`sudo systemctl daemon-reload && sudo systemctl restart kyber-api`).
  `:8000` is plain HTTP but DMZ-internal only (WAN→DMZ permits only `:443`; the hosts run no
  local firewall).

Push the code (app/ must be byte-identical on both nodes):

```
.\dmz-app-01\deploy.ps1 -Target 192.168.7.11
```

## 5. nginx as the L7 load balancer (BOTH nodes, identical)

Replace the single `proxy_pass` from `03-rest-api.md` §5 with an `upstream` across both
backends, on **app-01 and app-02**:

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
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
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
sudo ln -sf /etc/nginx/sites-available/kyber-api /etc/nginx/sites-enabled/kyber-api
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

`listen 443` is wildcard, so each node's nginx automatically answers on the VIP whenever it
holds it. nginx runs on **both** nodes at all times; the passive health checks
(`max_fails`/`fail_timeout`) drop a dead backend so traffic shifts to the survivor.

## 6. TLS certificate on app-02 (same `api.kyber.local` service)

**On `kyber-ldap`** — authorize app-02 to manage the existing service:

```
kinit admin
ipa service-add-host HTTP/api.kyber.local --hosts=kyber-app-02.kyber.local
```

**On `kyber-app-02`** — request the cert (same block as `03-rest-api.md` §4); certmonger tracks
and renews it and reloads nginx:

```
sudo mkdir -p /etc/ssl/kyber
sudo ipa-getcert request \
  -K HTTP/api.kyber.local \
  -N CN=api.kyber.local \
  -D api.kyber.local \
  -f /etc/ssl/kyber/api.crt \
  -k /etc/ssl/kyber/api.key \
  -C "systemctl reload nginx"
sudo ipa-getcert list      # -> MONITORING
```

Both nodes now serve a valid `api.kyber.local` cert, so the VIP presents a trusted cert
regardless of which node holds it.

## 7. keepalived — the floating VIP (BOTH nodes)

```
sudo apt -y install keepalived
```

The VIP is dual-stack, so IPv4 and IPv6 are **separate** `vrrp_instance` blocks. app-01 is the
preferred MASTER (higher priority); a health check drops its priority below app-02 if nginx
dies so the VIP migrates.

**`/etc/keepalived/keepalived.conf` on `kyber-app-01` (MASTER):**

```
vrrp_script chk_nginx {
    script "/usr/bin/systemctl is-active --quiet nginx"
    interval 2
    weight -60
    fall 2
    rise 2
}

vrrp_instance VI_4 {
    state MASTER
    interface ens160
    virtual_router_id 74
    priority 150
    advert_int 1
    authentication { auth_type PASS; auth_pass <shared-secret> }
    virtual_ipaddress { 192.168.7.100/24 }
    track_script { chk_nginx }
}

vrrp_instance VI_6 {
    state MASTER
    interface ens160
    virtual_router_id 75
    priority 150
    advert_int 1
    virtual_ipaddress { 2001:1470:fffd:99::100/64 }
    track_script { chk_nginx }
}
```

**`/etc/keepalived/keepalived.conf` on `kyber-app-02` (BACKUP)** — identical except
`state BACKUP` and `priority 100` in both instances (keep the same `virtual_router_id` 74/75 and
the same `auth_pass`).

```
sudo systemctl enable --now keepalived
```

> **ESXi:** keep the default (no `use_vmac`) — keepalived adds the VIP as a *secondary* address
> on the real NIC and sends gratuitous ARP/NA from the physical MAC, which the vSwitch passes
> without security changes. If the VIP fails to take over on failover, check the `sk07-dmz`
> port-group security ("Forged transmits" / "MAC address changes" = Accept) and confirm
> `use_vmac` is not set.

## 8. Point `api.kyber.local` at the VIP (on `kyber-ldap`)

The `api` record currently resolves to app-01 (`.10`/`::10`); repoint it to the VIP:

```
kinit admin
ipa dnsrecord-del kyber.local api --a-rec=192.168.7.10  --aaaa-rec=2001:1470:fffd:99::10
ipa dnsrecord-add kyber.local api --a-rec=192.168.7.100 --aaaa-rec=2001:1470:fffd:99::100
```

Clear stale/negative caches so clients pick up the change immediately:

```
# on kyber-rtr (operational mode) — the forwarder cached the old answer
reset dns forwarding all
# on each client (e.g. ws-01)
sudo resolvectl flush-caches
getent ahosts api.kyber.local        # -> 192.168.7.100 + 2001:1470:fffd:99::100
```

## 9. Acceptance (S3.7 / S3.12)

```
# VIP is on the MASTER
ip -br addr show ens160        # on app-01: shows 192.168.7.100 + ::100 ; on app-02: not present

# reachable + HTTP/2 + valid cert, from a CA-trusting client (ws-01/ws-02)
curl -sI --http2 https://api.kyber.local/customers | grep -i '^HTTP'   # HTTP/2 200
curl -6 https://api.kyber.local/health                                 # IPv6 path

# load distribution — both backends serve (watch uvicorn access logs on BOTH nodes)
for i in $(seq 10); do curl -s https://api.kyber.local/health >/dev/null; done

# FAILOVER (the S3.7 acceptance): kill the active node's web tier
#   on app-01:  sudo systemctl stop nginx kyber-api
#   within ~2-3s the VIP moves to app-02 (ip -br addr show ens160 on app-02), and:
curl -s https://api.kyber.local/health        # still 200
#   restore: on app-01  sudo systemctl start kyber-api nginx   -> VIP returns to app-01

# single-backend death (no VIP move needed): stop only `kyber-api` on one node
#   -> nginx upstream serves from the peer

# writes still work through the VIP (carol is in api-writers)
curl -u carol:<pw> -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' \
  -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'   # 201
```

> **Remaining SPOF (by decision):** PostgreSQL is single-primary on app-01. Losing app-01
> entirely takes the data tier down. Future hardening: streaming replication + manual promote,
> or automatic failover with **Patroni on the S4 etcd cluster**. The external DNAT (I1) to expose
> the VIP at `88.200.24.237:443` is also deferred.
