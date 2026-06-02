# kyber (sk07) — Test Runbooks

Acceptance tests for everything that is **actually built**, derived from the brief
(`original-instructions.txt`), the task plan (`kyber-project-plan.md`), the live router
state (`vyos/snapshot-config.boot`), and the per-host runbooks. Each test is a paste-able
command with the **exact expected result** for the as-built configuration — these feed the
"Test results" section of the technical report (plan §4 item 15).

Tests are split by **scope** (the subsystem under test and where you run it):

| File | Scope | Covers (plan IDs) |
|---|---|---|
| [`01-router-networking.md`](01-router-networking.md) | Router base, NAT, NPTv6, DHCP/DHCPv6/SLAAC, split-DNS, NTP, SNMP, NetFlow export | B3/B4/B5, N1, N2, N3, N4, N5, N8, N9 |
| [`02-firewall.md`](02-firewall.md) | Zone-based dual-stack firewall (positive + negative) | N6 |
| [`03-vpn.md`](03-vpn.md) | OpenVPN remote access + live FreeIPA auth | N7 |
| [`04-directory-and-dns.md`](04-directory-and-dns.md) | FreeIPA directory (users/groups/CA) + internal authoritative DNS | S1, S2 |
| [`05-rest-api.md`](05-rest-api.md) | REST API: CRUD, content-negotiation, TLS/HTTP2/IPv6, LDAP auth, persistence, HA, external publish (DNAT+SNI) | S3.1–S3.9, S3.7, I1 |
| [`06-monitoring.md`](06-monitoring.md) | Prometheus + exporters + Grafana, ntopng NetFlow analysis | S5, S6 |
| [`07-clients-and-ipv6only.md`](07-clients-and-ipv6only.md) | Heterogeneous clients (Ubuntu + Windows), IPv6-only segment | S8, S9 |
| [`08-etcd-patroni-ha.md`](08-etcd-patroni-ha.md) | etcd RAFT cluster + Patroni PostgreSQL auto-failover | S4, S3.7 (DB tier) |

---

## Completed-task matrix (the source of these tests)

Every row below is **built and verifiable**. "Evidence" is where the build is proven in the
repo. Tasks are only listed if the snapshot or a runbook confirms them — nothing aspirational.

| ID | Task | Evidence | Test |
|---|---|---|---|
| B3 | Hostname/domain/timezone/NTP-client | `snapshot system{}` | 01 §1 |
| B4 | Dual-stack addressing (4 ifaces) + default routes + forwarding | `snapshot interfaces{}`, `protocols static` | 01 §1–2 |
| B5 | Baseline internet connectivity (v4+v6) | operational | 01 §2 |
| N1 | NAT44 source masquerade (internal+DMZ → WAN) | `snapshot nat source` rule 100/110 | 01 §3 |
| N2 | NPTv6 (ULA `fd07:1:1:1::/64` ↔ `…9b::/64`) | `snapshot nat66` | 01 §4 (+07 §3 end-to-end) |
| N3 | DHCPv4 (pool+reservations), DHCPv6-stateful, SLAAC, RAs | `snapshot service dhcp/dhcpv6/router-advert` | 01 §5 |
| N4 | DNS forwarding + split DNS (`kyber.local`→FreeIPA, reverse) | `snapshot service dns forwarding` | 01 §6 |
| N5 | NTP relay to internal+DMZ | `snapshot service ntp` | 01 §7 |
| N6 | Zone-based dual-stack firewall | `snapshot firewall{}`, `network/firewall-policy.md` | 02 (all) |
| N7 | OpenVPN remote access, FreeIPA password auth, split-tunnel | `snapshot interfaces openvpn vtun0`, `pki`, runbook 08 | 03 (all) |
| N8 | SNMPv2c agent `kyber-ro`, source-restricted to mon | `snapshot service snmp` | 01 §8 |
| N9 | NetFlow v9 export (all 4 ifaces → mon:2055) | `snapshot system flow-accounting` | 01 §9 (+06 §3 collector) |
| I1 | DNAT `88.200.24.237:443` → VIP `192.168.7.100` + SNI edge | `snapshot nat destination` rule 100, runbooks 06/10 | 05 §9 |
| S1 | FreeIPA directory: users `alice/bob/carol/dave/luka/urban`, groups, CA | runbooks `dmz-ldap/04–07`, `kyber-ipa-ca.crt` | 04 §1–3 |
| S2 | Internal authoritative DNS (FreeIPA BIND, A/AAAA/PTR/SRV) | runbook `dmz-ldap/05`, snapshot forwarder→.30 | 04 §4 |
| S3.1/3.4 | PostgreSQL + `customers`/`orders` schema + persistence | runbook `dmz-app-01/02`, `app/models.py` | 05 §6 |
| S3.2/3.3 | REST CRUD + content negotiation (JSON/XML/HTML) | `app/main.py`, `app/serialization.py` | 05 §1–3 |
| S3.5/3.6/3.9 | TLS (FreeIPA cert) + HTTP/2 + IPv6 | runbook `dmz-app-01/03` §4–5 | 05 §4–5 |
| S3.8 | LDAP-gated writes (`api-writers`: carol✓ dave✗) | `app/auth.py` | 05 §7 |
| S3.7 | HA web tier: app-02 + keepalived VIP + active-active nginx | runbook `dmz-app-01/04` | 05 §8 |
| S4 | etcd 3-node RAFT (app-01/app-02/mon witness), mutual TLS; Patroni = consumer | runbook `dmz-app-01/05` | 08 §1–6 |
| S3.7-DB | PostgreSQL auto-failover (Patroni primary + hot standby, async repl) | runbook `dmz-app-01/05` | 08 §7–8 |
| S5 | Prometheus + node/snmp exporters + Grafana (HTTPS) | runbooks `dmz-mon/02–03` | 06 §1–4 |
| S6 | ntopng NetFlow analysis (top-talkers) | runbook `dmz-mon/04` | 06 §5 |
| S8 | IPv6-only host via SLAAC (ULA only, no IPv4) | runbook `ipv6/00`, snapshot RA eth3 | 07 §3 |
| S9 | Heterogeneous clients: Ubuntu `ws-01` + Windows `ws-02` | runbooks `ws-01/`, `ws-02/` | 07 §1–2 |

### Explicitly **not** tested (not built / not executed — by decision)

These appear in the plan but are out of scope for testing because there is no built evidence:

- **S7 Suricata IDS**, **S3.10 HTTP/3**, **S3.11 GraphQL** — optional, not built.
- **I2 public DNS** — no real domain; external clients use a local `hosts` file (see 05 §9).

> **S4 etcd + Patroni is now deployed** and tested in [`08-etcd-patroni-ha.md`](08-etcd-patroni-ha.md).
> Patroni's primary + hot standby replaces the former single-primary PostgreSQL SPOF, so that
> caveat in [`05-rest-api.md`](05-rest-api.md) §8 no longer applies.

---

## Global prerequisites & access model

Read once; every file assumes these.

1. **Router access is VPN-only.** The snapshot's `WAN→LOCAL` permits only ICMP echo and
   `udp/1194` — there is **no `tcp/22` from WAN**. To run any `kyber-rtr` `show` command you
   must either use the **ESXi console**, or bring up the VPN first ([`03-vpn.md`](03-vpn.md))
   and then `ssh vyos@10.7.99.1`. Router commands below are **operational mode** (`vyos@kyber-rtr:~$`);
   from configure mode prefix them with `run`.

2. **Reaching DMZ / internal hosts.** LAN workstations `kyber-ws-01` / `kyber-ws-02` sit on the
   internal segment and need no VPN — run client tests on them directly. To reach a server from
   off-LAN, bring up the VPN (you then route to `10.7.0.0/24` + `192.168.7.0/24`) and
   `ssh <user>@<host>`, or hop through the router: `ssh -J vyos@10.7.99.1 <user>@<host-ip>`.

3. **Host / address quick-reference (as built):**

   | Host | IPv4 | IPv6 | Notes |
   |---|---|---|---|
   | `kyber-rtr` | eth0 `88.200.24.237`, eth1 `10.7.0.1`, eth2 `192.168.7.1`, eth3 — | `…98::2`, `…9a::1`, `…99::1`, eth3 `fd07:1:1:1::1` | VPN tunnel `10.7.99.1` / `fd07:99::1` |
   | `kyber-app-01` | `192.168.7.10` | `2001:1470:fffd:99::10` | PostgreSQL primary, API, keepalived MASTER |
   | `kyber-app-02` | `192.168.7.11` | `2001:1470:fffd:99::11` | API, keepalived BACKUP |
   | **API VIP** | `192.168.7.100` | `2001:1470:fffd:99::100` | `api.kyber.local` resolves here |
   | `kyber-mon` | `192.168.7.20` | `2001:1470:fffd:99::20` | Prometheus/Grafana/ntopng |
   | `kyber-ldap` | `192.168.7.30` | `2001:1470:fffd:99::30` | FreeIPA, internal DNS, CA |
   | `kyber-ws-01` | `10.7.0.100`–`.200` (dynamic) | `…9a::100`–`::1ff` | Ubuntu client |
   | `kyber-ws-02` | `10.7.0.100`–`.200` (dynamic) | `…9a::100`–`::1ff` | Windows client |
   | `kyber-ipv6` | — none — | `fd07:1:1:1::…` (SLAAC) | IPv6-only |

4. **TLS trust.** Tests that hit `https://*.kyber.local` need the FreeIPA CA
   (`dmz-ldap/kyber-ipa-ca.crt`) trusted. `ws-01`/`ws-02` are already set up
   ([`ws-01/01-…`](../ws-01/01-ca-trust-and-acceptance.md)); elsewhere pass `--cacert dmz-ldap/kyber-ipa-ca.crt`.

5. **Test users** (created with `--random`; substitute the captured passwords):
   `carol` ∈ `api-writers` (API writes succeed), `dave` ∉ (403). `alice`,`bob`,`luka`,`urban`
   ∈ `vpn-users` (VPN allowed); `dave` ∉ (VPN rejected). `alice` ∈ `admins`.

6. **Pass/fail.** A test passes only if the **Expect** line matches. Where a result depends on
   live data (lease addresses, dashboard graphs) the expectation is described, not a literal.
