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
against the host's own principal (always self-authorized — no `service-add` needed). The SAN is
the **FQDN only** — everything connects by hostname (etcd advertised URLs, `etcdctl`, Patroni all
use FQDNs), so no IP SANs are needed. The `etcd` service runs as `User=etcd`, so it must be able
to read **both** files. `ipa-getcert` sets owner/perms with `-o/-m` (key) and `-O/-M` (cert) and
reasserts them on renewal, but has **no group flag**:
- **cert** (`node.crt`) is public → `-M 0644` makes it world-readable so `etcd` can read it (this
  is the file whose default `0600 root:root` makes the service fail with `open … permission
  denied`).
- **key** (`node.key`) is secret → keep it `0640` and give group read to `etcd` (later also
  `postgres`). Since there's no group flag, the `-C` after-command `chgrp`s it on each renewal, and
  §2 does it once explicitly the first time (at request time the `etcd` group doesn't exist yet —
  the package creates it — so the after-command's `chgrp` is allowed to no-op here).

Run on each node with its own FQDN:

```sh
sudo mkdir -p /etc/etcd/tls
sudo ipa-getcert request \
  -K host/kyber-app-01.kyber.local -N CN=kyber-app-01.kyber.local \
  -D kyber-app-01.kyber.local \
  -f /etc/etcd/tls/node.crt -k /etc/etcd/tls/node.key \
  -o root -m 0640 -M 0644 \
  -C "chgrp etcd /etc/etcd/tls/node.key 2>/dev/null || true; systemctl try-reload-or-restart etcd"
sudo ipa-getcert list -f /etc/etcd/tls/node.crt   # poll until status: MONITORING (then crt+key exist)
```

> **Do NOT add `-A <ip>` SANs.** The IPA CA rejects a CSR whose IP SAN has no PTR record
> (`3009 ... does not have PTR record`) — and the v6 reverse zone isn't set up. The FQDN `-D` SAN
> is all TLS needs here: peers/`etcdctl`/Patroni connect by name, and the Postgres reuse runs with
> `sslmode=require` (encrypt, no hostname check). If a request is stuck `CA_REJECTED`, clear it
> with `sudo ipa-getcert stop-tracking -i <ID>` + `sudo rm -f /etc/etcd/tls/node.*`, then re-run.

> certmonger reaches `MONITORING` a few seconds after the request (it goes
> `GENERATING_KEY_PAIR → SUBMITTING → MONITORING`); don't run the next steps until it does, or
> `node.key`/`node.crt` won't exist yet.

## 2. etcd 3-node cluster — mutual TLS, dual-stack (S4.1)

Install on all three; the package autostarts a throwaway single-node default, so reset it:

```sh
sudo apt -y install etcd-server etcd-client      # confirm: etcd --version >= 3.4
sudo systemctl stop etcd && sudo rm -rf /var/lib/etcd/*
# the package just created the `etcd` group → set the key's group now (the §1 -C does this on
# renewals, but couldn't the first time because the group didn't exist yet). Also force the cert
# world-readable in case it was requested before the §1 `-M 0644` was added:
sudo chgrp etcd /etc/etcd/tls/node.key && sudo chmod 640 /etc/etcd/tls/node.key
sudo chmod 644 /etc/etcd/tls/node.crt
```

`/etc/default/etcd` — change the per-node lines (`ETCD_NAME`, the two `…ADVERTISE…`, and the two
`…LISTEN…`, which use this node's real addresses); the cluster + TLS lines are identical on every
node. **app-01** shown — substitute each node's own FQDN/addresses from the table below:

```sh
sudo tee /etc/default/etcd >/dev/null <<'ETCD'
ETCD_NAME="app-01"
ETCD_INITIAL_ADVERTISE_PEER_URLS="https://kyber-app-01.kyber.local:2380"
ETCD_ADVERTISE_CLIENT_URLS="https://kyber-app-01.kyber.local:2379"
ETCD_LISTEN_PEER_URLS="https://192.168.7.10:2380,https://[2001:1470:fffd:99::10]:2380"
ETCD_LISTEN_CLIENT_URLS="https://192.168.7.10:2379,https://[2001:1470:fffd:99::10]:2379,https://127.0.0.1:2379"
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

| Host | `ETCD_NAME` / advertise FQDN | `LISTEN` addresses (peer/client) |
|---|---|---|
| app-01 | `app-01` / `kyber-app-01.kyber.local` | `192.168.7.10` + `[2001:1470:fffd:99::10]` |
| app-02 | `app-02` / `kyber-app-02.kyber.local` | `192.168.7.11` + `[2001:1470:fffd:99::11]` |
| mon    | `mon`    / `kyber-mon.kyber.local`    | `192.168.7.20` + `[2001:1470:fffd:99::20]` |

> **Bind explicit per-node addresses, NOT `0.0.0.0`+`[::]` together.** With the default
> `net.ipv6.bindv6only=0`, the IPv6 wildcard `[::]` also claims the IPv4 space, so listing both
> wildcards on one port makes etcd collide with *itself* → `bind: address already in use` (with
> nothing in `ss`). Advertised URLs stay FQDNs (resolve dual-stack via IPA DNS, match the cert).

Start all three roughly together, then verify. A client cert is required (the node cert is one),
but `node.key` is `root:etcd 0640` — unreadable to your login user — so run `etcdctl` with **sudo**
(root reads the key). Pass TLS + endpoints as **flags**, not `ETCDCTL_*` env, since `sudo` resets
the environment by default:

```sh
sudo systemctl enable --now etcd

EP=https://kyber-app-01.kyber.local:2379,https://kyber-app-02.kyber.local:2379,https://kyber-mon.kyber.local:2379
TLS="--cacert=/etc/ipa/ca.crt --cert=/etc/etcd/tls/node.crt --key=/etc/etcd/tls/node.key"
sudo etcdctl $TLS --endpoints=$EP member list -w table
sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table   # 3 rows, exactly one IS LEADER=true (S4.3)
sudo etcdctl $TLS --endpoints=$EP endpoint health --cluster
```

> etcd ≥3.4 defaults to the v3 API, so no `ETCDCTL_API=3` is needed. If you prefer the env form,
> use `sudo -E` **and** ensure your sudoers allows preserving the environment — flags are simpler.

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

`/etc/patroni/config.yml` — on app-02 change only `name` and the `…connect_address`/`listen`
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
    - hostssl postgres rewind_user 192.168.7.10/32 scram-sha-256
    - hostssl postgres rewind_user 192.168.7.11/32 scram-sha-256
    - hostssl postgres rewind_user 2001:1470:fffd:99::10/128 scram-sha-256
    - hostssl postgres rewind_user 2001:1470:fffd:99::11/128 scram-sha-256

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
sudo chown postgres:postgres /etc/patroni/config.yml && sudo chmod 600 /etc/patroni/config.yml
sudo install -d -o postgres -g postgres -m 0700 /var/lib/postgresql/16/patroni   # PG rejects 0755 — must be 0700/0750
sudo usermod -aG etcd postgres               # postgres reads the TLS key (SSL + etcd client)
```

> The Ubuntu `patroni` package's unit (`systemctl cat patroni`) hard-codes
> **`/etc/patroni/config.yml`** in both `ConditionPathExists=` and `ExecStart=`, and runs as
> `User=postgres` — so the file **must** be at that exact path (a wrong name like `patroni.yml`
> makes `ConditionPathExists` fail and the service never starts). Use `config.yml` as written above.

**Start app-01 first** (it has the `bootstrap:` block → `initdb`, becomes primary, claims the
leader key). **Then app-02** (sees the leader, clones via `pg_basebackup`, comes up as replica):

```sh
sudo systemctl enable --now patroni
sudo patronictl -c /etc/patroni/config.yml list   # app-01 Leader; after app-02 starts: + app-02 Replica
```

> `patronictl` must run with **sudo** (or as the `postgres` user): the config is `0600
> postgres:postgres`, so your login user can't read it — without sudo you get
> `config file … not existing or no read rights`.

> **If app-02 never appears as Replica:** check `sudo journalctl -u patroni -n 40`. A half-finished
> earlier attempt leaves a partial PGDATA, so Patroni tries crash-recovery instead of a fresh clone
> and may fail with `data directory … has invalid permissions` (must be `0700`). Reset it and let
> Patroni re-clone from the leader:
> ```sh
> sudo systemctl stop patroni
> sudo rm -rf /var/lib/postgresql/16/patroni
> sudo install -d -o postgres -g postgres -m 0700 /var/lib/postgresql/16/patroni
> sudo systemctl restart patroni
> ```

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

Confirm it replicated, **on app-02**: `sudo -u postgres psql "host=127.0.0.1 sslmode=require user=postgres dbname=kyber" -c '\dt'`
(fold `dbname` into the conninfo string — don't also pass `-d`, or psql treats the whole string as
a role name: `role "host=127.0.0.1 …" does not exist`).

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
> SQLAlchemy's parser is the fussy bit. If it chokes on the query-string form, keep
> `KYBER_DATABASE_URL=postgresql+psycopg://kyber_api:CHANGE_ME@/kyber` and move the rest into
> `connect_args` in `database.py` (no logic change) — `database.py` reads the URL verbatim, so
> this is the only edit:
>
> ```python
> engine = create_engine(
>     DATABASE_URL,
>     pool_pre_ping=True,
>     connect_args={
>         "host": "192.168.7.10,192.168.7.11",
>         "target_session_attrs": "read-write",
>         "sslmode": "require",
>     },
> )
> ```
>
> Then `.\dmz-app-01\deploy.ps1 -Target 192.168.7.{10,11}` to push both nodes. `pool_pre_ping=True`
> (already set) drops dead/demoted connections and re-probes — that's what carries the API across a
> failover.

## 7. Supersede `02` §6

`02-postgresql.md` §6 (manual `listen_addresses` + per-host `pg_hba` on the apt cluster) is now
**obsolete** — Patroni owns `postgresql.conf`/`pg_hba`/replication. No action beyond §3's disable;
noted so the two runbooks don't read as contradictory.

## 8. The etcd consumer (S4.2)

Patroni **is** the consumer: every leadership decision is a quorum-confirmed RAFT write to the
leader key/lease under `/kyber/kyber-pg/`, which is what prevents split-brain. Inspect it (reusing
the `sudo etcdctl` + `$TLS`/`$EP` pattern from §2 — re-export them if you're in a new shell):

```sh
sudo etcdctl $TLS --endpoints=$EP get --prefix /kyber/kyber-pg/ -w fields | grep -E 'Key|Value' | head
```

## 9. Acceptance (S4.3, S4.4, S3.7 DB)

```sh
sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table   # 3 members, one IS LEADER (re-export $TLS/$EP from §2 in a new shell)
sudo patronictl -c /etc/patroni/config.yml list    # one Leader + one Replica, both running, low Lag

# DB failover: stop Patroni on the PRIMARY → standby auto-promotes within ~ttl
sudo systemctl stop patroni                     # on the primary node
sudo patronictl -c /etc/patroni/config.yml list     # survivor is now Leader
curl -u carol:<pw> -X POST https://api.kyber.local/orders -H 'Content-Type: application/json' \
  -d '{"customer_id":1,"product":"Failover","quantity":1,"amount":1.00}'   # still 201
# restore: sudo systemctl start patroni  → rejoins as replica (ideally via pg_rewind)
#   pg_rewind connects to the new primary as rewind_user against the `postgres` db —
#   the `hostssl postgres rewind_user …` lines in §4's pg_hba are what let it rejoin
#   without a full pg_basebackup reclone. If failback hangs/errors, check those exist.
#
#   COMMON: the old primary comes back as "start failed" with
#     FATAL: data directory "/var/lib/postgresql/16/patroni" has invalid permissions
#   When Patroni tears down the diverged old-timeline data to re-clone, the dir can be
#   recreated 0755; PostgreSQL refuses anything looser than 0700/0750. Wipe + recreate at
#   0700 and let it clone fresh from the current leader (this is also what `patronictl
#   reinit kyber-pg <member>` does, minus the perms fix):
#     sudo systemctl stop patroni
#     sudo rm -rf /var/lib/postgresql/16/patroni
#     sudo install -d -o postgres -g postgres -m 0700 /var/lib/postgresql/16/patroni
#     sudo systemctl start patroni        # -> "replica has been created using basebackup" -> secondary

# etcd leader re-election: stop etcd on the IS-LEADER node
sudo systemctl stop etcd
sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table   # new leader in ~1-2s; Patroni unaffected (quorum 2/3)
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
