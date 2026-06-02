# Test 08 — etcd RAFT cluster + Patroni PostgreSQL auto-failover (S4, S3.7 DB tier)

Validates the 3-node etcd RAFT cluster (`kyber-app-01`, `kyber-app-02`, `kyber-mon` witness) and
**Patroni** — the etcd consumer that turns the former single-primary PostgreSQL SPOF into an
auto-failover **primary + hot standby**. Mirrors `dmz-app-01/05-etcd-patroni-ha.md` §2/§8/§9.

**Where to run:** the cluster nodes (`app-01`/`app-02`/`mon`) for etcd & Patroni; a CA-trusting
client for the API failover check. Covers **S4.1–S4.4** and the **S3.7 database tier**.

> As built (Option A): etcd mutual TLS from the FreeIPA CA, dual-stack listeners, cluster token
> `kyber-etcd`. Patroni scope `kyber-pg`, namespace `/kyber/`, **async** streaming replication,
> primary on app-01 / standby on app-02, mon = etcd witness (no Postgres). The API uses a
> multi-host libpq URL (`target_session_attrs=read-write`) so it always lands on the live primary.

---

## 0. Setup — etcdctl needs sudo + TLS flags

**Run on:** any cluster node (e.g. `kyber-app-01`). Re-export in every new shell.

```sh
EP=https://kyber-app-01.kyber.local:2379,https://kyber-app-02.kyber.local:2379,https://kyber-mon.kyber.local:2379
TLS="--cacert=/etc/ipa/ca.crt --cert=/etc/etcd/tls/node.crt --key=/etc/etcd/tls/node.key"
```

> Run `etcdctl` with **sudo** — `node.key` is `root:etcd 0640`, unreadable to a login user. Pass
> TLS/endpoints as **flags** (not `ETCDCTL_*` env): `sudo` resets the environment.

> **Failover tests (§7, §8) briefly interrupt the data tier** — run them in a maintenance window.

## 1. etcd cluster healthy — 3 members, one leader (S4.1, S4.3)

**Run on:** `kyber-app-01`

```sh
sudo etcdctl $TLS --endpoints=$EP member list -w table              # app-01, app-02, mon
sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table  # 3 rows, exactly one IS LEADER=true
sudo etcdctl $TLS --endpoints=$EP endpoint health --cluster         # all "is healthy"
```

**Expect:** three started members, **exactly one** leader, all healthy.

## 2. Mutual TLS is enforced (S4.1)

**Run on:** `kyber-app-01`

```sh
# no client cert -> rejected (ETCD_CLIENT_CERT_AUTH=true)
etcdctl --endpoints=https://kyber-app-01.kyber.local:2379 --cacert=/etc/ipa/ca.crt member list
```

**Expect:** **fails** ("certificate required" / context deadline) — a client without the
IPA-issued cert can't talk to etcd. The §1 call (with `$TLS`) succeeding is the positive control.

## 3. Dual-stack listeners

**Run on:** each node

```sh
sudo ss -ltnp | grep -E ':2379|:2380'
```

**Expect:** etcd listens on **this node's IPv4 and IPv6** DMZ addresses for both client (`2379`)
and peer (`2380`) — e.g. on app-01: `192.168.7.10` and `[2001:1470:fffd:99::10]`. Advertised URLs
are FQDNs that resolve dual-stack via FreeIPA DNS (and match the cert SAN).

## 4. Patroni cluster state (S3.7 DB tier)

**Run on:** `kyber-app-01` or `kyber-app-02`

```sh
sudo patronictl -c /etc/patroni/config.yml list
```

**Expect:** cluster `kyber-pg` shows one **Leader** (Running) and one **Replica** (Running,
State `streaming`, low `Lag in MB`). app-01 is normally the Leader.

> `patronictl` needs **sudo** (config is `0600 postgres:postgres`).

## 5. Streaming replication is live (persistence + standby in sync)

**Run on:** a client (create on the primary via the API), then `kyber-app-02` (read on the standby)

```sh
# client — create a row (carol ∈ api-writers)
curl -u carol:PASS -X POST https://api.kyber.local/customers \
  -H 'Content-Type: application/json' -d '{"name":"Repl Check","email":"repl@kyber.local"}'   # 201

# app-02 (standby) — confirm it replicated
sudo -u postgres psql "host=127.0.0.1 sslmode=require user=postgres dbname=kyber" \
  -c "select email from customers where email='repl@kyber.local'"
```

**Expect:** the row appears on the standby — async replication works. The standby is read-only:

```sh
sudo -u postgres psql "host=127.0.0.1 sslmode=require user=postgres dbname=kyber" \
  -c "insert into customers(name,email) values('x','x@y')"     # ERROR: cannot ... in a read-only transaction
```

## 6. The etcd consumer — Patroni's leader key (S4.2)

**Run on:** `kyber-app-01`

```sh
sudo etcdctl $TLS --endpoints=$EP get --prefix /kyber/kyber-pg/ -w fields | grep -E 'Key|Value' | head
```

**Expect:** keys under `/kyber/kyber-pg/` (`/leader`, `/members/…`, `/config`). Patroni stores the
RAFT-quorum-confirmed leader lease here — that consensus write is exactly the "service using RAFT"
the brief asks for (S4.2), doing real work (preventing split-brain), not a toy KV demo.

## 7. Database auto-failover — the headline test (S4.3, S3.7 DB)

**Run on:** the **primary** node + a client

```sh
# identify the current Leader, then stop Patroni on it:
sudo patronictl -c /etc/patroni/config.yml list      # note which node is Leader
sudo systemctl stop patroni                          # on the Leader node

# within ~ttl (30s) the standby auto-promotes:
sudo patronictl -c /etc/patroni/config.yml list      # the survivor is now Leader
```

**Run on:** a client — writes still succeed (libpq multi-host finds the new primary):

```sh
curl -s -o /dev/null -w '%{http_code}\n' -u carol:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Failover","quantity":1,"amount":1.00}'   # 201
```

**Restore:** `sudo systemctl start patroni` on the stopped node → it rejoins as **Replica** (via
`pg_rewind`).

**Expect:** the standby promotes with no manual step; the API POST still returns `201`. This retires
the app-01 PostgreSQL SPOF. (If the old primary won't rejoin with `data directory … has invalid
permissions`, reinit it per runbook 05 §9 — wipe + recreate the data dir `0700`, or
`sudo patronictl -c /etc/patroni/config.yml reinit kyber-pg <member>`.)

## 8. etcd leader re-election (S4.3)

**Run on:** the etcd **leader** node

```sh
sudo systemctl stop etcd                                            # on the IS-LEADER node
sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table  # new leader in ~1-2s (quorum 2/3)
sudo patronictl -c /etc/patroni/config.yml list                     # Postgres HA UNAFFECTED
sudo systemctl start etcd                                           # restore -> rejoins
```

**Expect:** the remaining two members elect a new etcd leader within ~1-2s; Patroni/PostgreSQL keep
running (the mon witness preserves quorum when one app node's etcd is down). Capture the
`endpoint status` and `patronictl list` tables plus the surviving `POST` for the report (S4.4).
