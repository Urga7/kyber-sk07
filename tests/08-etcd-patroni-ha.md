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

**Output:**
```
kyber@kyber-app-01:~$ sudo etcdctl $TLS --endpoints=$EP member list -w table              # app-01, app-02, mon
sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table  # 3 rows, exactly one IS LEADER=true
sudo etcdctl $TLS --endpoints=$EP endpoint health --cluster         # all "is healthy"
[sudo] password for kyber:
+------------------+---------+--------+---------------------------------------+---------------------------------------+------------+
|        ID        | STATUS  |  NAME  |              PEER ADDRS               |             CLIENT ADDRS              | IS LEARNER |
+------------------+---------+--------+---------------------------------------+---------------------------------------+------------+
| 184924851938f8b9 | started | app-02 | https://kyber-app-02.kyber.local:2380 | https://kyber-app-02.kyber.local:2379 |      false |
| 1e17addb243e6438 | started |    mon |    https://kyber-mon.kyber.local:2380 |    https://kyber-mon.kyber.local:2379 |      false |
| 26110833b7b3a41b | started | app-01 | https://kyber-app-01.kyber.local:2380 | https://kyber-app-01.kyber.local:2379 |      false |
+------------------+---------+--------+---------------------------------------+---------------------------------------+------------+
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
|               ENDPOINT                |        ID        | VERSION | DB SIZE | IS LEADER | IS LEARNER | RAFT TERM | RAFT INDEX | RAFT APPLIED INDEX | ERRORS |
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
| https://kyber-app-02.kyber.local:2379 | 184924851938f8b9 |  3.4.30 |   78 kB |     false |      false |       301 |        195 |                195 |        |
|    https://kyber-mon.kyber.local:2379 | 1e17addb243e6438 |  3.4.30 |   74 kB |      true |      false |       301 |        195 |                195 |        |
| https://kyber-app-01.kyber.local:2379 | 26110833b7b3a41b |  3.4.30 |   70 kB |     false |      false |       301 |        195 |                195 |        |
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
https://kyber-app-01.kyber.local:2379 is healthy: successfully committed proposal: took = 16.185408ms
https://kyber-mon.kyber.local:2379 is healthy: successfully committed proposal: took = 17.89343ms
https://kyber-app-02.kyber.local:2379 is healthy: successfully committed proposal: took = 22.032746ms

```

## 2. Mutual TLS is enforced (S4.1)

**Run on:** `kyber-app-01`

```sh
# no client cert -> rejected (ETCD_CLIENT_CERT_AUTH=true)
etcdctl --endpoints=https://kyber-app-01.kyber.local:2379 --cacert=/etc/ipa/ca.crt member list
```

**Expect:** **fails** ("certificate required" / context deadline) — a client without the
IPA-issued cert can't talk to etcd. The §1 call (with `$TLS`) succeeding is the positive control.

**Output:**
```
kyber@kyber-app-01:~$ etcdctl --endpoints=https://kyber-app-01.kyber.local:2379 --cacert=/etc/ipa/ca.crt member list
{"level":"warn","ts":"2026-06-03T08:37:24.396285+0200","caller":"clientv3/retry_interceptor.go:62","msg":"retrying of unary invoker failed","target":"etcd-endpoints://0xc000007dc0/kyber-app-01.kyber.local:2379","attempt":0,"error":"rpc error: code = DeadlineExceeded desc = latest balancer error: last connection error: write tcp [2001:1470:fffd:99::10]:34606->[2001:1470:fffd:99::10]:2379: write: broken pipe"}
Error: context deadline exceeded

```

## 3. Dual-stack listeners

**Run on:** each node

```sh
sudo ss -ltnp | grep -E ':2379|:2380'
```

**Expect:** etcd listens on **this node's IPv4 and IPv6** DMZ addresses for both client (`2379`)
and peer (`2380`) — e.g. on app-01: `192.168.7.10` and `[2001:1470:fffd:99::10]`. Advertised URLs
are FQDNs that resolve dual-stack via FreeIPA DNS (and match the cert SAN).

**Output:**
```
kyber@kyber-app-01:~$ sudo ss -ltnp | grep -E ':2379|:2380'
LISTEN 0      4096              192.168.7.10:2379      0.0.0.0:*    users:(("etcd",pid=13280,fd=10))
LISTEN 0      4096              192.168.7.10:2380      0.0.0.0:*    users:(("etcd",pid=13280,fd=7))
LISTEN 0      4096                 127.0.0.1:2379      0.0.0.0:*    users:(("etcd",pid=13280,fd=9))
LISTEN 0      4096   [2001:1470:fffd:99::10]:2380         [::]:*    users:(("etcd",pid=13280,fd=8))
LISTEN 0      4096   [2001:1470:fffd:99::10]:2379         [::]:*    users:(("etcd",pid=13280,fd=11))


kyber@kyber-app-02:~$ sudo ss -ltnp | grep -E ':2379|:2380'
[sudo] password for kyber:
LISTEN 0      4096                 127.0.0.1:2379      0.0.0.0:*    users:(("etcd",pid=7319,fd=9))
LISTEN 0      4096              192.168.7.11:2379      0.0.0.0:*    users:(("etcd",pid=7319,fd=10))
LISTEN 0      4096              192.168.7.11:2380      0.0.0.0:*    users:(("etcd",pid=7319,fd=7))
LISTEN 0      4096   [2001:1470:fffd:99::11]:2380         [::]:*    users:(("etcd",pid=7319,fd=8))
LISTEN 0      4096   [2001:1470:fffd:99::11]:2379         [::]:*    users:(("etcd",pid=7319,fd=11))


kyber@kyber-mon:~$ sudo ss -ltnp | grep -E ':2379|:2380'
[sudo] password for kyber:
LISTEN 0      4096                 127.0.0.1:2379      0.0.0.0:*    users:(("etcd",pid=22868,fd=11))
LISTEN 0      4096              192.168.7.20:2380      0.0.0.0:*    users:(("etcd",pid=22868,fd=7))
LISTEN 0      4096              192.168.7.20:2379      0.0.0.0:*    users:(("etcd",pid=22868,fd=12))
LISTEN 0      4096   [2001:1470:fffd:99::20]:2379         [::]:*    users:(("etcd",pid=22868,fd=10))
LISTEN 0      4096   [2001:1470:fffd:99::20]:2380         [::]:*    users:(("etcd",pid=22868,fd=8))

```

## 4. Patroni cluster state (S3.7 DB tier)

**Run on:** `kyber-app-01` or `kyber-app-02`

```sh
sudo patronictl -c /etc/patroni/config.yml list
```

**Expect:** cluster `kyber-pg` shows one **Leader** (Running) and one **Replica** (Running,
State `streaming`, low `Lag in MB`). app-01 is normally the Leader.

**Output:**
```
kyber@kyber-app-01:~$ sudo patronictl -c /etc/patroni/config.yml list
+ Cluster: kyber-pg (7646822141529040852) ----+----+-----------+
| Member | Host         | Role    | State     | TL | Lag in MB |
+--------+--------------+---------+-----------+----+-----------+
| app-01 | 192.168.7.10 | Replica | streaming |  3 |         0 |
| app-02 | 192.168.7.11 | Leader  | running   |  3 |           |
+--------+--------------+---------+-----------+----+-----------+


kyber@kyber-app-02:~$ sudo patronictl -c /etc/patroni/config.yml list
+ Cluster: kyber-pg (7646822141529040852) ----+----+-----------+
| Member | Host         | Role    | State     | TL | Lag in MB |
+--------+--------------+---------+-----------+----+-----------+
| app-01 | 192.168.7.10 | Replica | streaming |  3 |         0 |
| app-02 | 192.168.7.11 | Leader  | running   |  3 |           |
+--------+--------------+---------+-----------+----+-----------+

```

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

**Output:**
```
kyber@kyber-app-01:~$ sudo -u postgres psql "host=127.0.0.1 sslmode=require user=postgres dbname=kyber" \
  -c "select email from customers where email='repl@kyber.local'"
      email
------------------
 repl@kyber.local
(1 row)

kyber@kyber-app-01:~$ sudo -u postgres psql "host=127.0.0.1 sslmode=require user=postgres dbname=kyber" \
  -c "insert into customers(name,email) values('x','x@y')"     # ERROR: cannot ... in a read-only transaction
ERROR:  cannot execute INSERT in a read-only transaction

```

## 6. The etcd consumer — Patroni's leader key (S4.2)

**Run on:** `kyber-app-01`

```sh
sudo etcdctl $TLS --endpoints=$EP get --prefix /kyber/kyber-pg/ -w fields | grep -E 'Key|Value' | head
```

**Expect:** keys under `/kyber/kyber-pg/` (`/leader`, `/members/…`, `/config`). Patroni stores the
RAFT-quorum-confirmed leader lease here — that consensus write is exactly the "service using RAFT"
the brief asks for (S4.2), doing real work (preventing split-brain), not a toy KV demo.

**Output:**
```
kyber@kyber-app-01:~$ sudo etcdctl $TLS --endpoints=$EP get --prefix /kyber/kyber-pg/ -w fields | grep -E 'Key|Value' | head
"Key" : "/kyber/kyber-pg/config"
"Value" : "{\"ttl\":30,\"loop_wait\":10,\"retry_timeout\":10,\"synchronous_mode\":false,\"postgresql\":{\"use_pg_rewind\":true,\"parameters\":{\"wal_level\":\"replica\",\"hot_standby\":\"on\",\"max_wal_senders\":10,\"max_replication_slots\":10}}}"
"Key" : "/kyber/kyber-pg/history"
"Value" : "[[1,88337576,\"no recovery target specified\",\"2026-06-02T18:20:57.447371+02:00\",\"app-02\"],[2,151035472,\"no recovery target specified\",\"2026-06-03T06:50:59.578939+02:00\",\"app-02\"]]"
"Key" : "/kyber/kyber-pg/initialize"
"Value" : "7646822141529040852"
"Key" : "/kyber/kyber-pg/leader"
"Value" : "app-02"
"Key" : "/kyber/kyber-pg/members/app-01"
"Value" : "{\"conn_url\":\"postgres://192.168.7.10:5432/postgres\",\"api_url\":\"http://192.168.7.10:8008/patroni\",\"state\":\"running\",\"role\":\"replica\",\"version\":\"3.2.2\",\"xlog_location\":151045344,\"replication_state\":\"streaming\",\"timeline\":3}"
```

## 7. Database auto-failover — the headline test (S4.3, S3.7 DB)

**Run on:** the **primary** node + a client

```sh
# identify the current Leader, then stop Patroni on it:
sudo patronictl -c /etc/patroni/config.yml list      # note which node is Leader
sudo systemctl stop patroni                          # on the Leader node
sleep 5
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

**Output:**
```
kyber@kyber-app-02:~$ sudo patronictl -c /etc/patroni/config.yml list      # note which node is Leader
sudo systemctl stop patroni                          # on the Leader node
sleep 5
# within ~ttl (30s) the standby auto-promotes:
sudo patronictl -c /etc/patroni/config.yml list      # the survivor is now Leader
+ Cluster: kyber-pg (7646822141529040852) ----+----+-----------+
| Member | Host         | Role    | State     | TL | Lag in MB |
+--------+--------------+---------+-----------+----+-----------+
| app-01 | 192.168.7.10 | Replica | streaming |  3 |         0 |
| app-02 | 192.168.7.11 | Leader  | running   |  3 |           |
+--------+--------------+---------+-----------+----+-----------+
+ Cluster: kyber-pg (7646822141529040852) --+----+-----------+
| Member | Host         | Role    | State   | TL | Lag in MB |
+--------+--------------+---------+---------+----+-----------+
| app-01 | 192.168.7.10 | Leader  | running |  4 |           |
| app-02 | 192.168.7.11 | Replica | stopped |    |   unknown |
+--------+--------------+---------+---------+----+-----------+

kyber@kyber-ws-01:~$ curl -s -o /dev/null -w '%{http_code}\n' -u carol:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"product":"Failover","quantity":1,"amount":1.00}'   # 201
201

```

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

**Output:**
```
kyber@kyber-mon:~$ sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table
[sudo] password for kyber:
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
|               ENDPOINT                |        ID        | VERSION | DB SIZE | IS LEADER | IS LEARNER | RAFT TERM | RAFT INDEX | RAFT APPLIED INDEX | ERRORS |
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
| https://kyber-app-02.kyber.local:2379 | 184924851938f8b9 |  3.4.30 |   78 kB |     false |      false |       301 |        221 |                221 |        |
|    https://kyber-mon.kyber.local:2379 | 1e17addb243e6438 |  3.4.30 |   78 kB |      true |      false |       301 |        221 |                221 |        |
| https://kyber-app-01.kyber.local:2379 | 26110833b7b3a41b |  3.4.30 |   82 kB |     false |      false |       301 |        221 |                221 |        |
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
kyber@kyber-mon:~$ sudo systemctl stop etcd
kyber@kyber-mon:~$ sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table
{"level":"warn","ts":"2026-06-03T08:57:38.630782+0200","caller":"clientv3/retry_interceptor.go:62","msg":"retrying of unary invoker failed","target":"etcd-endpoints://0xc0002a21c0/kyber-app-01.kyber.local:2379","attempt":0,"error":"rpc error: code = DeadlineExceeded desc = latest balancer error: last connection error: connection error: desc = \"transport: Error while dialing dial tcp [2001:1470:fffd:99::20]:2379: connect: connection refused\""}
Failed to get the status of endpoint https://kyber-mon.kyber.local:2379 (context deadline exceeded)
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
|               ENDPOINT                |        ID        | VERSION | DB SIZE | IS LEADER | IS LEARNER | RAFT TERM | RAFT INDEX | RAFT APPLIED INDEX | ERRORS |
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
| https://kyber-app-02.kyber.local:2379 | 184924851938f8b9 |  3.4.30 |   78 kB |      true |      false |       302 |        222 |                222 |        |
| https://kyber-app-01.kyber.local:2379 | 26110833b7b3a41b |  3.4.30 |   82 kB |     false |      false |       302 |        222 |                222 |        |
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
kyber@kyber-mon:~$ sudo systemctl start etcd
kyber@kyber-mon:~$ sudo etcdctl $TLS --endpoints=$EP endpoint status --cluster -w table
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
|               ENDPOINT                |        ID        | VERSION | DB SIZE | IS LEADER | IS LEARNER | RAFT TERM | RAFT INDEX | RAFT APPLIED INDEX | ERRORS |
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+
| https://kyber-app-02.kyber.local:2379 | 184924851938f8b9 |  3.4.30 |   78 kB |      true |      false |       302 |        223 |                223 |        |
|    https://kyber-mon.kyber.local:2379 | 1e17addb243e6438 |  3.4.30 |   78 kB |     false |      false |       302 |        223 |                223 |        |
| https://kyber-app-01.kyber.local:2379 | 26110833b7b3a41b |  3.4.30 |   82 kB |     false |      false |       302 |        223 |                223 |        |
+---------------------------------------+------------------+---------+---------+-----------+------------+-----------+------------+--------------------+--------+

```
