# 05 — etcd 3-node RAFT cluster + Patroni PostgreSQL HA (S4, S3.7 DB tier)

Stands up the **etcd RAFT cluster** (S4) and uses it as the consensus backend for **Patroni**,
which turns the single-primary PostgreSQL SPOF from [`04-app-02-and-ha.md`](04-app-02-and-ha.md)
into an **auto-failover primary + hot standby**. Patroni *is* the etcd consumer the brief wants
(S4.2) — real, load-bearing work, not a toy KV demo.

```
   etcd RAFT cluster (control plane):  app-01 ── app-02 ── mon(witness, no Postgres)
                                          │ Patroni holds the leader key/lease in etcd
        Patroni@app-01  ── async streaming repl ──►  Patroni@app-02
        PG16 PRIMARY (.10/::10)                      PG16 STANDBY (.11/::11)
                          ▲ both APIs use a MULTI-HOST libpq URL (target_session_attrs=read-write)
        kyber-api (uvicorn) on app-01 + app-02   ← unchanged nginx upstream + keepalived VIP (04)
```

**Decisions (Option A):**
- **mon = etcd witness** (3rd vote, no Postgres) → losing one *app* node never costs etcd quorum.
- **Asynchronous** replication (HA, never blocks writes; small failover loss window — fine here).
- **Full mutual TLS** (FreeIPA CA) on etcd peer **and** client, and Patroni→etcd — authenticates
  the control plane that governs failover, consistent with the rest of the build (LDAPS/`hostssl`).
- **Client-side discovery, no middlebox:** the API lists *both* DB nodes; libpq keeps the
  read-write primary. No HAProxy / no DB VIP to become its own SPOF. `database.py` already sets
  `pool_pre_ping=True`, so the **API change is env-only, no code**.

> All traffic here (etcd `2379`/`2380`, Patroni `8008`, Postgres `5432`) is **intra-DMZ/L2**,
> never crosses `kyber-rtr`, and the hosts run no local firewall → **no router rules needed**.
> Admin SSH is VPN-only now: `ssh -J vyos@10.7.99.1 <user>@192.168.7.{10,11,20}`.

---

## 0. Prerequisites
- All three hosts FreeIPA-enrolled with FQDN hostnames (app-01/-02 in `04` §2, mon in
  `dmz-mon/03` §3): `hostname -f` resolves, and `/etc/ipa/ca.crt` exists (the TLS trust anchor).
- Clocks synced to `192.168.7.1` (RAFT + TLS are time-sensitive).
- NIC `ens160`; addresses .10/.11/.20 + `::10/::11/::20`.

## 1. TLS certs from the FreeIPA CA (each node)

One certmonger cert per node, used for both etcd's server identity **and** as Patroni's etcd
client cert (the IPA `caIPAserviceCert` profile carries `serverAuth`+`clientAuth`). Requested
against the host's own principal (always self-authorized — no `service-add` needed). SANs **must**
include the FQDN and **both** IP families. Run on each node with its own values:

```sh
sudo mkdir -p /etc/etcd/tls
sudo ipa-getcert request \
  -K host/kyber-app-01.kyber.local -N CN=kyber-app-01.kyber.local \
  -D kyber-app-01.kyber.local -A 192.168.7.10 -A 2001:1470:fffd:99::10 \
  -f /etc/etcd/tls/node.crt -k /etc/etcd/tls/node.key \
  -C "systemctl try-reload-or-restart etcd"
sudo ipa-getcert list -f /etc/etcd/tls/node.crt   # wait for status MONITORING
```

| Host | `-K`/`-N`/`-D` FQDN | `-A` addresses |
|---|---|---|
| app-01 | `kyber-app-01.kyber.local` | `192.168.7.10`, `2001:1470:fffd:99::10` |
| app-02 | `kyber-app-02.kyber.local` | `192.168.7.11`, `2001:1470:fffd:99::11` |
| mon    | `kyber-mon.kyber.local`    | `192.168.7.20`, `2001:1470:fffd:99::20` |

> TLS errors at cluster-form time are almost always a missing SAN — check with
> `openssl x509 -in /etc/etcd/tls/node.crt -noout -text | grep -A1 'Alternative'`.

## 2. etcd 3-node cluster — mutual TLS, dual-stack (S4.1)

Install on all three; the package autostarts a throwaway single-node default, so reset it:

```sh
sudo apt -y install etcd-server etcd-client      # confirm: etcd --version >= 3.4
sudo systemctl stop etcd && sudo rm -rf /var/lib/etcd/*
sudo chgrp etcd /etc/etcd/tls/node.key && sudo chmod 640 /etc/etcd/tls/node.key
```

`/etc/default/etcd` — change only the three per-node lines (`ETCD_NAME` + the two `…ADVERTISE…`);
the rest is identical on every node. app-01 shown:

```sh
sudo tee /etc/default/etcd >/dev/null <<'ETCD'
ETCD_NAME="app-01"
ETCD_INITIAL_ADVERTISE_PEER_URLS="https://kyber-app-01.kyber.local:2380"
ETCD_ADVERTISE_CLIENT_URLS="https://kyber-app-01.kyber.local:2379"
ETCD_LISTEN_PEER_URLS="https://0.0.0.0:2380,https://[::]:2380"
ETCD_LISTEN_CLIENT_URLS="https://0.0.0.0:2379,https://[::]:2379,https://127.0.0.1:2379"
ETCD_INITIAL_CLUSTER="app-01=https://kyber-app-01.kyber.local:2380,app-02=https://kyber-app-02.kyber.local:2380,mon=https://kyber-mon.kyber.local:2380"
ETCD_INITIAL_CLUSTER_TOKEN="kyber-etcd"
ETCD_INITIAL_CLUSTER_STATE="new"
ETCD_DATA_DIR="/var/lib/etcd/kyber"
ETCD_CERT_FILE="/etc/etcd/tls/node.crt"
ETCD_KEY_FILE="/etc/etcd/tls/node.key"
ETCD_TRUSTED_CA_FILE="/etc/ipa/ca.crt"
ETCD_CLIENT_CERT_AUTH="true"
ETCD_PEER_CERT_FILE="/etc/etcd/tls/node.crt"
ETCD_PEER_KEY_FILE="/etc/etcd/tls/node.key"
ETCD_PEER_TRUSTED_CA_FILE="/etc/ipa/ca.crt"
ETCD_PEER_CLIENT_CERT_AUTH="true"
ETCD
```

> FQDNs in the advertised URLs (resolve dual-stack via IPA DNS, match the cert CN); wildcard
> `LISTEN_*` so each node answers on both stacks.

Start all three roughly together, then verify (a client cert is required — the node cert is one):

```sh
sudo systemctl enable --now etcd
export ETCDCTL_API=3 ETCDCTL_CACERT=/etc/ipa/ca.crt \
  ETCDCTL_CERT=/etc/etcd/tls/node.crt ETCDCTL_KEY=/etc/etcd/tls/node.key \
  ETCDCTL_ENDPOINTS=https://kyber-app-01.kyber.local:2379,https://kyber-app-02.kyber.local:2379,https://kyber-mon.kyber.local:2379
etcdctl member list -w table
etcdctl endpoint status --cluster -w table        # 3 rows, exactly one IS LEADER=true (S4.3)
etcdctl endpoint health --cluster
```

## 3. Migrate off the existing setup (both nodes)

> **Maintenance window.** From here until §6 the API has no database — between disabling app-01's
> Postgres (below) and Patroni coming up + the API rewire, reads/writes error. Run §3–§6 in one go.

Patroni manages its own data dir + config, so retire what `02`/`04` left, and make sure **both**
nodes have the PostgreSQL 16 **binaries** Patroni drives (`initdb`/`pg_basebackup`/`pg_rewind`).

**app-01** — has the live DB from `02`; dump it as a safety capture, then disable the apt cluster:

```sh
sudo -u postgres pg_dump -Fc -d kyber -f /var/lib/postgresql/kyber-prePatroni.dump
sudo systemctl disable --now postgresql
sudo pg_ctlcluster 16 main stop 2>/dev/null || true   # leaves the old data dir intact as a fallback
```

**app-02** — per `04` it has *no* local Postgres (only the API, talking to app-01). Install the
server for its binaries, then immediately disable the apt cluster it autostarts so it can't
contend with Patroni for `:5432`:

```sh
sudo apt -y install postgresql-16
sudo systemctl disable --now postgresql
sudo pg_ctlcluster 16 main stop 2>/dev/null || true
```

> Don't pre-seed app-02's data — its Patroni clones the whole cluster from the primary via
> `pg_basebackup` when it joins (§4).

## 4. Patroni on app-01 + app-02 (S3.7 DB auto-failover)

```sh
sudo apt -y install patroni       # confirm patronictl is on PATH
```

> If Patroni logs `module 'etcd3' not available`, add the client lib:
> `sudo apt -y install python3-etcd3` (or `pip install 'patroni[etcd3]'`).

`/etc/patroni/patroni.yml` — on app-02 change only `name` and the `…connect_address`/`listen`
hosts. Role passwords live here only (root-owned, uncommitted):

```yaml
scope: kyber-pg
namespace: /kyber/
name: app-01                                   # app-02: app-02

restapi:
  listen: 0.0.0.0:8008
  connect_address: 192.168.7.10:8008           # app-02: 192.168.7.11:8008

etcd3:
  hosts: [kyber-app-01.kyber.local:2379, kyber-app-02.kyber.local:2379, kyber-mon.kyber.local:2379]
  protocol: https
  cacert: /etc/ipa/ca.crt
  cert: /etc/etcd/tls/node.crt
  key: /etc/etcd/tls/node.key

bootstrap:                                     # applied only by the first node to start
  dcs:
    ttl: 30
    loop_wait: 10
    retry_timeout: 10
    synchronous_mode: false                    # asynchronous (this build)
    postgresql:
      use_pg_rewind: true
      parameters: {wal_level: replica, hot_standby: "on", max_wal_senders: 10, max_replication_slots: 10}
  initdb: [{encoding: UTF8}, data-checksums]
  pg_hba:
    - local all all peer
    - hostssl all all 127.0.0.1/32 scram-sha-256
    - hostssl all all ::1/128 scram-sha-256
    - hostssl kyber kyber_api 192.168.7.10/32 scram-sha-256
    - hostssl kyber kyber_api 192.168.7.11/32 scram-sha-256
    - hostssl kyber kyber_api 2001:1470:fffd:99::10/128 scram-sha-256
    - hostssl kyber kyber_api 2001:1470:fffd:99::11/128 scram-sha-256
    - hostssl replication replicator 192.168.7.10/32 scram-sha-256
    - hostssl replication replicator 192.168.7.11/32 scram-sha-256
    - hostssl replication replicator 2001:1470:fffd:99::10/128 scram-sha-256
    - hostssl replication replicator 2001:1470:fffd:99::11/128 scram-sha-256

postgresql:
  listen: 0.0.0.0:5432
  connect_address: 192.168.7.10:5432           # app-02: 192.168.7.11:5432
  data_dir: /var/lib/postgresql/16/patroni     # NEW dir, not the apt 'main'
  bin_dir: /usr/lib/postgresql/16/bin
  authentication:
    superuser:   {username: postgres,     password: "CHANGE_ME_SUPER"}
    replication: {username: replicator,   password: "CHANGE_ME_REPL"}
    rewind:      {username: rewind_user,  password: "CHANGE_ME_REWIND"}
  parameters:
    ssl: "on"
    ssl_cert_file: /etc/etcd/tls/node.crt      # reuse the IPA node cert for Postgres TLS too
    ssl_key_file: /etc/etcd/tls/node.key
    ssl_ca_file: /etc/ipa/ca.crt
```

```sh
sudo chown postgres:postgres /etc/patroni/patroni.yml && sudo chmod 600 /etc/patroni/patroni.yml
sudo install -d -o postgres -g postgres /var/lib/postgresql/16/patroni
sudo usermod -aG etcd postgres               # postgres reads the TLS key (SSL + etcd client)
```

> Verify the unit's config path with `systemctl cat patroni` (some builds expect
> `/etc/patroni.yml`); symlink if it differs.

**Start app-01 first** (it has the `bootstrap:` block → `initdb`, becomes primary, claims the
leader key). **Then app-02** (sees the leader, clones via `pg_basebackup`, comes up as replica):

```sh
sudo systemctl enable --now patroni
patronictl -c /etc/patroni/patroni.yml list   # app-01 Leader; after app-02 starts: + app-02 Replica
```

## 5. Recreate role/db/schema/seed (on the primary)

```sh
sudo -u postgres psql "host=127.0.0.1 sslmode=require user=postgres" <<'SQL'
CREATE ROLE kyber_api WITH LOGIN PASSWORD 'CHANGE_ME';     -- same pw as the API env
CREATE DATABASE kyber OWNER kyber_api;
SQL
```

Re-apply the schema + seed from [`02-postgresql.md`](02-postgresql.md) §3–4 against `-d kyber`,
**or** restore the dump instead:

```sh
sudo -u postgres pg_restore -d kyber --no-owner /var/lib/postgresql/kyber-prePatroni.dump
sudo -u postgres psql -d kyber -c 'ALTER TABLE customers OWNER TO kyber_api; ALTER TABLE orders OWNER TO kyber_api;'
```

Confirm it replicated: `sudo -u postgres psql "host=127.0.0.1 sslmode=require user=postgres" -d kyber -c '\dt'` on app-02.

## 6. Rewire the API to the multi-host primary (env-only, BOTH nodes)

Replace `KYBER_DATABASE_URL` in `/etc/kyber-api.env` (set in `03` §3) on both nodes — libpq keeps
whichever listed host is read-write, so the API always lands on the current primary:

```sh
KYBER_DATABASE_URL=postgresql+psycopg://kyber_api:CHANGE_ME@/kyber?host=192.168.7.10,192.168.7.11&target_session_attrs=read-write&sslmode=require
```
```sh
sudo systemctl restart kyber-api
```

> Verify the form on the box: psycopg3 passes multi-`host`/`target_session_attrs` to libpq;
> SQLAlchemy's parser is the fussy bit. If it chokes, pass them via `connect_args` in
> `database.py` instead (no logic change). `pool_pre_ping=True` (already set) drops dead/demoted
> connections and re-probes — that's what carries the API across a failover.

## 7. Supersede `02` §6

`02-postgresql.md` §6 (manual `listen_addresses` + per-host `pg_hba` on the apt cluster) is now
**obsolete** — Patroni owns `postgresql.conf`/`pg_hba`/replication. No action beyond §3's disable;
noted so the two runbooks don't read as contradictory.

## 8. The etcd consumer (S4.2)

Patroni **is** the consumer: every leadership decision is a quorum-confirmed RAFT write to the
leader key/lease under `/kyber/kyber-pg/`, which is what prevents split-brain. Inspect it:

```sh
etcdctl get --prefix /kyber/kyber-pg/ -w fields | grep -E 'Key|Value' | head
```

## 9. Acceptance (S4.3, S4.4, S3.7 DB)

```sh
etcdctl endpoint status --cluster -w table     # 3 members, one IS LEADER
patronictl -c /etc/patroni/patroni.yml list    # one Leader + one Replica, both running, low Lag

# DB failover: stop Patroni on the PRIMARY → standby auto-promotes within ~ttl
sudo systemctl stop patroni                     # on the primary node
patronictl -c /etc/patroni/patroni.yml list     # survivor is now Leader
curl -u carol:<pw> -X POST https://api.kyber.local/orders -H 'Content-Type: application/json' \
  -d '{"customer_id":1,"product":"Failover","quantity":1,"amount":1.00}'   # still 201
# restore: sudo systemctl start patroni  → rejoins as replica (pg_rewind)

# etcd leader re-election: stop etcd on the IS-LEADER node
sudo systemctl stop etcd
etcdctl endpoint status --cluster -w table       # new leader in ~1-2s; Patroni unaffected (quorum 2/3)
# restore: sudo systemctl start etcd
```

Capture the `endpoint status` / `patronictl list` tables and the surviving `POST` for the report
(S4.4 / S3.13), and document the topology + failover in [`README.md`](README.md).

## 10. Follow-ups
- **app-01 DB SPOF retired** — losing app-01 auto-promotes app-02. etcd needs 2/3 members up;
  mon is the cheap witness covering one app-node loss.
- **Synchronous** zero-loss: `patronictl edit-config` → `synchronous_mode: true` (writes block if
  the standby is down).
- **app-03 (Option B)** and **I1 DNAT** remain independent and deferred.
