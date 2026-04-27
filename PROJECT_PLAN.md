# kyber (sk07) — Communication Protocols Project Plan

This document is the canonical task list. It replaces the original Slovene brief
with precise, atomic, dependency-aware tasks, split into work that **must be done
together** and work that **two people can do in parallel**.

Conventions used below:
- `[N#]` = Networking track (Person A) task
- `[S#]` = Services track (Person B) task
- `[B#]` = Bootstrap / shared task
- `[I#]` = Integration task (joint, after parallel phase)
- "Acceptance" lines tell you when the task is *actually* done.

---

## 0. Project constants (single source of truth)

### 0.1 Identity
| Item | Value |
|---|---|
| Company name | `kyber` |
| Group | `sk07` (group #7) |
| Internal DNS domain | `kyber.sk07.lab` |
| External DNS domain | choose one you can put records under, or fall back to using only the IP for external — document the choice |

### 0.2 IPv4 plan
| Segment | Network | Router IP | DHCP behaviour |
|---|---|---|---|
| External / WAN | 88.200.24.128/25 | LRK GW = 88.200.24.129; ours = **88.200.24.237/25** | static |
| Internal (users) | **10.7.0.0/24** | 10.7.0.1 | dynamic pool 10.7.0.100–10.7.0.200 |
| DMZ (servers)   | **192.168.7.0/24** | 192.168.7.1 | static reservations only (per-MAC) |
| ipv6-only       | — no IPv4 — | — | — |

### 0.3 IPv6 plan (assigned `2001:1470:fffd:98::/62` → 4× /64)
| /64 prefix | Use | Notes |
|---|---|---|
| `2001:1470:fffd:98::/64` | External link to LRK | **must** be this one. Router WAN IPv6 = `2001:1470:fffd:98::2/64`. GW = `2001:1470:fffd:98::1`. |
| `2001:1470:fffd:99::/64` | Internal segment | Router IP `…99::1`. **SLAAC** here. |
| `2001:1470:fffd:9a::/64` | DMZ segment | Router IP `…9a::1`. **DHCPv6 stateful** here. |
| `2001:1470:fffd:9b::/64` | NPTv6 *outer* prefix for ipv6-only | Not assigned on any link directly. |

**ipv6-only inner ULA**: `fd07:7::/64`. NPTv6 maps `fd07:7::/64` ↔ `2001:1470:fffd:9b::/64`.

This satisfies: 1 segment with SLAAC (internal), 1 with DHCPv6 (DMZ), 1 ipv6-only segment with NPTv6, plus static IPv6 on the router.

### 0.4 ESXi port groups (already exist on the VyOS VM)
| NIC | Port group | VyOS interface | Purpose |
|---|---|---|---|
| 1 | `LRK-public-internet` | `eth0` | WAN |
| 2 | `sk07-internal` | `eth1` | users |
| 3 | `sk07-dmz` | `eth2` | servers |
| 4 | `sk07-ipv6only` | `eth3` | IPv6-only |

### 0.5 Hostnames
`kyber-rtr-01`, `kyber-app-01`, `kyber-app-02`, `kyber-mon-01`, `kyber-ldap-01`, `kyber-ws-lin`, `kyber-ws-win`, `kyber-v6host`.

### 0.6 VM inventory (target end-state)
| VM | OS | Segment | Roles |
|---|---|---|---|
| `kyber-rtr-01` (existing) | VyOS 1.4.4 | all 4 | router, FW, NAT, NPTv6, DHCP/DHCPv6, DNS forwarder, NTP, VPN endpoint |
| `kyber-app-01` | Ubuntu Server LTS | DMZ | nginx (TLS, HTTP/2), REST API, PostgreSQL primary, etcd-1, internal authoritative DNS |
| `kyber-app-02` | Ubuntu Server LTS | DMZ | nginx (TLS, HTTP/2), REST API replica, PostgreSQL replica, etcd-2 |
| `kyber-mon-01` | Ubuntu Server LTS | DMZ | Prometheus, Grafana, snmp_exporter, etcd-3, *(opt)* Suricata, *(opt)* ntopng |
| `kyber-ldap-01` | Ubuntu Server LTS | DMZ | FreeIPA (LDAP + Kerberos + CA) |
| `kyber-ws-lin` | Ubuntu Desktop | Internal | end-user client (Linux) |
| `kyber-ws-win` | Windows 10/11 | Internal | end-user client (Windows — heterogeneous OS) |
| `kyber-v6host` | Ubuntu Server LTS | ipv6-only | demonstrates NPTv6 |

If resources are tight you can collapse `kyber-ldap-01` into `kyber-app-01`, but FreeIPA prefers its own host. The 3 etcd nodes are non-negotiable (RAFT requires ≥3).

### 0.7 Repo layout (GitHub)
```
/README.md                ← copy of §0
/network/
   README.md              ← topology, diagrams, VyOS rationale
   firewall-policy.md     ← written policy (matrix in N6)
   diagrams/              ← drawio/excalidraw + PNG exports
/snapshots/
   0000-baseline-config.boot
   0001-<topic>-config.boot
   ...
/services/
   rest-api/
   ldap/
   monitoring/
   etcd/
   vpn/
/report/
   kyber-report.md
   kyber-report.pdf
```

---

## 1. Phase B — Bootstrap (BOTH of you, before splitting)

These must land before Person A and Person B can usefully fork. Do them on a screen-share or as one PR.

- [ ] **B1** Create the GitHub repo with the layout above. Commit §0 verbatim into `/README.md`.
- [ ] **B2** SSH to existing VyOS, run `show configuration commands` and `save`, then export `config.boot` (e.g. `scp` from `/config/config.boot`) into `/snapshots/0000-baseline-config.boot`.
- [ ] **B3** On VyOS set: `system host-name kyber-rtr-01`, `system domain-name kyber.sk07.lab`, time-zone `Europe/Ljubljana`, NTP servers (`0.si.pool.ntp.org`, `1.si.pool.ntp.org`), an admin user with **SSH public-key** auth, then `set service ssh disable-password-authentication`.
- [ ] **B4** Configure all four interfaces with the IPv4 + IPv6 addresses from §0.2 and §0.3. **Do not configure firewall rules yet** (default-allow). Default routes: v4 → `88.200.24.129`, v6 → `2001:1470:fffd:98::1`.
- [ ] **B5** From the router itself verify:
   - `ping 8.8.8.8` ✓
   - `ping 88.200.24.129` ✓
   - `ping6 2001:1470:fffd:98::1` ✓
   - `ping6 2001:4860:4860::8888` ✓
- [ ] **B6** Decide and document in `/README.md` the technology choices:
   - VPN: **WireGuard** (recommended — simplest in VyOS) — fall back to OpenVPN if you need LDAP password auth.
   - REST stack: **FastAPI + Uvicorn** behind **nginx** (recommended) — has trivial JSON/XML/HTML negotiation and async DB access.
   - DB: **PostgreSQL** with streaming replication.
   - Monitoring: **Prometheus + Grafana** + `snmp_exporter` and `node_exporter`.
   - LDAP: **FreeIPA** (gives you LDAP, Kerberos, and an internal CA in one).
   - IDS (optional): **Suricata** on `kyber-mon-01`.
- [ ] **B7** Create the remaining VMs as bare Ubuntu/Windows installs, attached to the correct port groups, give them temporary static IPs (no services yet) so both of you can SSH in. App and DB will be wired up in Track S.
- [ ] **B8** Commit `/snapshots/0001-after-baseline-config.boot`.

✅ When B1–B8 are green, fork into Track N and Track S.

---

## 2. Phase 1 — Parallel work

Person A and Person B work independently. The only synchronisation points are: (a) when Person B's services are ready, Person A has to publish them via DNAT and firewall rules (handled in Phase 2 / I1), and (b) Person B needs to know the gateway / DNS / NTP IPs (already nailed down in §0).

---

### Track N — Networking (Person A) — owns `kyber-rtr-01`

#### N1. L3 baseline
- [ ] N1.1 Confirm interface IPs from B4 are still in place after reboot.
- [ ] N1.2 Add NAT44 source masquerade on `eth0` for `10.7.0.0/24` and `192.168.7.0/24`.
- [ ] N1.3 **Acceptance**: from `kyber-ws-lin` once it exists, `curl ifconfig.me` returns `88.200.24.237`.

#### N2. NPTv6 (the tricky one)
- [ ] N2.1 Configure `set nat66 source rule 10` translating inner `fd07:7::/64` → outer `2001:1470:fffd:9b::/64` on egress through `eth0`.
- [ ] N2.2 Configure `set nat66 destination rule 10` for the reverse path (incoming `2001:1470:fffd:9b::/64` → `fd07:7::/64`).
- [ ] N2.3 **Acceptance**: from `kyber-v6host` (only ULA on its NIC), `ping6 ipv6.google.com` works; `tcpdump -i eth0 ip6` on the router shows source = `2001:1470:fffd:9b::…`.

#### N3. DHCP / DHCPv6 / SLAAC / RA
- [ ] N3.1 DHCPv4 on `eth1` (internal): pool `10.7.0.100–10.7.0.200`, DNS = `10.7.0.1`, domain = `kyber.sk07.lab`.
- [ ] N3.2 DHCPv4 on `eth2` (DMZ): static-mapping per VM MAC: `app-01=192.168.7.10`, `app-02=192.168.7.11`, `mon-01=192.168.7.20`, `ldap-01=192.168.7.30`. (Plus a small dynamic pool `.100–.150` for ad-hoc test VMs.)
- [ ] N3.3 IPv6 internal: **SLAAC**. `set service router-advert interface eth1 prefix 2001:1470:fffd:99::/64 autonomous-flag true on-link-flag true`. Set `name-server` and `dnssl` options too.
- [ ] N3.4 IPv6 DMZ: **DHCPv6 stateful** with per-DUID reservations matching the IPv4 mapping (`…9a::10`, `::11`, `::20`, `::30`). RA with `managed-flag true other-config-flag true autonomous-flag false`.
- [ ] N3.5 IPv6 ipv6-only: SLAAC announcing `fd07:7::/64` on `eth3`.
- [ ] N3.6 **Acceptance**: a freshly-booted Ubuntu VM in `internal` gets a v4 lease + v6 SLAAC; one in `dmz` with reserved MAC gets the predictable v4 + v6 addresses; one in `ipv6only` gets only a ULA.

#### N4. DNS — split + forwarder
- [ ] N4.1 On VyOS: `set service dns forwarding listen-address 10.7.0.1`, same for the DMZ side, public upstreams = `1.1.1.1` and `2606:4700:4700::1111`.
- [ ] N4.2 For zone `kyber.sk07.lab`: `set service dns forwarding domain kyber.sk07.lab server 192.168.7.10` (the internal authoritative — it lives on `kyber-app-01`, set up by Track S).
- [ ] N4.3 The internal authoritative returns *private* records (e.g. `api.kyber.sk07.lab → 192.168.7.10`, AAAA `→ 2001:1470:fffd:9a::10`). The external view (set up by Track S in S2.3) returns the *public* address `88.200.24.237`. This is the split-DNS requirement.
- [ ] N4.4 **Acceptance**: from `kyber-ws-lin`, `dig api.kyber.sk07.lab` returns `192.168.7.10`. From a VPN-disconnected outside host, the same query returns `88.200.24.237`.

#### N5. NTP
- [ ] N5.1 `set service ntp listen-address 10.7.0.1` and `192.168.7.1`.
- [ ] N5.2 **Acceptance**: `chronyc sources` on `kyber-app-01` shows VyOS as a sync peer.

#### N6. Firewall (dual-stack, default-DROP)
First write `/network/firewall-policy.md` with the matrix below as plain English (per row, *why* the rule exists). Then encode it.

Zones: `WAN`, `INTERNAL`, `DMZ`, `V6ONLY`, `LOCAL`, `VPN`.

| From → To | Allowed |
|---|---|
| any → any | established/related |
| WAN → LOCAL | ICMP echo (rate-limit), WireGuard UDP/51820 |
| WAN → DMZ | ports of published services only (443, optional 80→443 redirect). **No raw SSH from WAN.** |
| WAN → INTERNAL | nothing |
| INTERNAL → WAN | all (regular outbound) |
| INTERNAL → DMZ | published service ports + SSH from a designated jump host MAC |
| INTERNAL → LOCAL | SSH, DNS, NTP, DHCP |
| DMZ → INTERNAL | nothing new (only return) |
| DMZ → WAN | apt mirrors (HTTPS), NTP, monitoring egress only — no general outbound |
| DMZ → LOCAL | DNS, NTP, SNMP responses to mon-01 |
| V6ONLY → WAN | all v6 outbound |
| WAN → V6ONLY | nothing new |
| VPN → INTERNAL/DMZ | as needed by remote workers (SSH, RDP, HTTPS) |
| LOCAL → any | all |

- [ ] N6.1 Encode the matrix as IPv4 + IPv6 rules (every rule needs both families).
- [ ] N6.2 **Acceptance**: from outside, `nmap -Pn 88.200.24.237` and `nmap -6 …` show only intentionally exposed ports. From DMZ, `nmap 10.7.0.0/24` is fully filtered.

#### N7. VPN
- [ ] N7.1 WireGuard listener on `eth0`, both v4 + v6. Allocate VPN client subnet `10.7.99.0/24` and `fd07:7:99::/64`.
- [ ] N7.2 Push routes for `10.7.0.0/24`, `192.168.7.0/24`, and v6 equivalents. Push DNS = `10.7.0.1`.
- [ ] N7.3 *(optional)* Authenticate against FreeIPA: easiest path is to script per-user keypair generation gated by an LDAP group check (`vpn-users`), since WireGuard itself is keys-only. If you need real LDAP password auth, swap in OpenVPN with `openvpn-auth-ldap`.
- [ ] N7.4 **Acceptance**: from your laptop off-LAN you connect, get tunnel addresses, `ssh 10.7.0.5` works, `dig api.kyber.sk07.lab` returns the internal IP.

#### N8. SNMP source
- [ ] N8.1 SNMPv3 on VyOS, read-only user with authPriv (SHA + AES). Bind only to interfaces facing `kyber-mon-01`.
- [ ] N8.2 Restrict by ACL to `192.168.7.20` only.
- [ ] N8.3 **Acceptance**: `snmpwalk -v3 -l authPriv …` from mon-01 returns interface counters.

#### N9. *(Optional)* NetFlow/sFlow export
- [ ] N9.1 Export NetFlow v9 from VyOS to `192.168.7.20:2055`.

#### N10. Snapshots & docs (continuous)
- [ ] After every meaningful commit, save `config.boot` as `/snapshots/NN-<topic>-config.boot`.
- [ ] Keep `/network/README.md` current with the topology diagram (Mermaid is fine for the source — render to PNG for the report).

---

### Track S — Services (Person B) — owns the application VMs

These tasks need only that Phase B is done (VMs reachable, gateway up, DNS+NTP available). They don't block on N6/N7.

#### S1. FreeIPA on `kyber-ldap-01`
- [ ] S1.1 Install FreeIPA server, realm `KYBER.SK07.LAB`, with integrated CA.
- [ ] S1.2 Create groups `users`, `admins`, `vpn-users`, `api-writers`.
- [ ] S1.3 Create at least 4 test users with distinct group memberships, e.g.:
   - `alice` ∈ admins, vpn-users
   - `bob` ∈ users, vpn-users
   - `carol` ∈ api-writers
   - `dave` ∈ users
- [ ] S1.4 Export the FreeIPA CA cert; both Track N and Track S will need it.
- [ ] S1.5 **Acceptance**: `ldapsearch -H ldaps://ldap.kyber.sk07.lab -D 'uid=alice,cn=users,…' -W` succeeds and shows group memberships.

#### S2. REST API on `kyber-app-01` + `kyber-app-02`
Pick 2 related resources. Recommended: `customers` (1) ↔ `orders` (N).

- [ ] S2.1 PostgreSQL on app-01 (primary) with streaming replication to app-02 (hot standby). Schema for both resources, foreign key from `orders.customer_id`.
- [ ] S2.2 FastAPI app implementing CRUD for both resources.
- [ ] S2.3 **Content negotiation** on `Accept`:
   - `application/json` ✓
   - `application/xml` ✓ (use `dicttoxml` or jinja XML template)
   - `text/html` ✓ (jinja-rendered table) or `text/csv`
- [ ] S2.4 **Auth**: `POST/PUT/DELETE` require Bearer token. `/login` performs LDAP simple-bind against FreeIPA; on success checks the user is in `api-writers`; if so, returns a short-lived JWT signed by the app's key. Reads (`GET`) are public.
- [ ] S2.5 nginx in front of FastAPI on each app VM:
   - `listen 443 ssl http2;` for HTTP/2
   - real cert from FreeIPA's CA (or Let's Encrypt if you set up a public DNS name with DNS-01) — **not** self-signed
   - HSTS, OCSP stapling
- [ ] S2.6 **HA**: `keepalived` between app-01 and app-02 with VIP `192.168.7.100` (and `2001:1470:fffd:9a::100`). DNAT (Track N owns this in I1) points the public IP at the VIP.
- [ ] S2.7 *(optional)* HTTP/3 with `listen 443 quic reuseport;` + `add_header Alt-Svc 'h3=":443"';` — nginx ≥1.25.
- [ ] S2.8 *(optional)* Mirror the same data via a GraphQL endpoint (`strawberry` for FastAPI).
- [ ] S2.9 **Internal authoritative DNS** for `kyber.sk07.lab` runs on app-01 (BIND or unbound-with-zones). Zone returns *internal* IPs. A separate "external" view returns `88.200.24.237` for the same names.
- [ ] S2.10 **Acceptance**:
   - `curl -H 'Accept: application/json' https://api.kyber.sk07.lab/customers` returns JSON
   - same with `application/xml` returns XML
   - same with `text/html` returns rendered HTML
   - `curl -X POST … /orders` with no token → 401; with `carol`'s token → 201; with `bob`'s token → 403
   - browser devtools shows protocol = `h2`
   - `pkill nginx` on app-01 → traffic still served via app-02 within ~5 s

#### S3. RAFT cluster (etcd) across app-01 / app-02 / mon-01
- [ ] S3.1 Install etcd on all three. TLS for both peer and client traffic, certs issued by FreeIPA's CA.
- [ ] S3.2 A small consumer that *demonstrably* uses RAFT. Two reasonable options:
   - tiny "leader status" web page: a sidecar on each app node uses etcd's leader-election to write the current leader's hostname; a `/leader` endpoint reads it back.
   - use etcd as the feature-flag store for the REST API (`/customers/feature/X` → reads etcd).
- [ ] S3.3 **Acceptance**: `etcdctl endpoint status --cluster -w table` shows a leader; `systemctl stop etcd` on the leader → a new leader is elected within 5 s; the consumer keeps working.

#### S4. Monitoring on `kyber-mon-01`
- [ ] S4.1 Prometheus scraping (interval ≤15 s — the brief explicitly asks for short intervals):
   - `snmp_exporter` against `kyber-rtr-01` (interface counters, CPU, memory)
   - `node_exporter` on every Linux VM
   - `nginx-prometheus-exporter` on app-01/02
   - `postgres_exporter` on app-01/02
   - etcd's native `/metrics`
- [ ] S4.2 Grafana with dashboards:
   - VyOS interface throughput (per interface, in/out, both v4 and v6 if possible)
   - REST API health (request rate, error rate, latency p50/p95)
   - etcd cluster (leader, members up, proposal latency)
   - Host (CPU/RAM/disk) for all Linux VMs
- [ ] S4.3 Grafana itself behind nginx + TLS at `https://mon.kyber.sk07.lab`.
- [ ] S4.4 **Acceptance**: dashboards visible, alerting rule fires when you stop nginx on app-01.

#### S5. *(Optional)* NetFlow analysis
- [ ] S5.1 ntopng on `kyber-mon-01` listening on UDP/2055 (matches N9).
- [ ] S5.2 Generate traffic (`iperf3`, big `apt` install). Screenshot top-talkers.

#### S6. *(Optional)* IDS/IPS
- [ ] S6.1 Suricata on `kyber-mon-01`. ESXi caveat: real port-mirroring requires promiscuous mode on the vSwitch; if your security policy or instructor disallows it, run Suricata locally on each app VM watching its own NIC instead — note this in the report.
- [ ] S6.2 Demos to capture:
   - `nmap -A 88.200.24.237` from outside → Suricata alert
   - `hydra -L users -P passwords ssh://10.7.0.5` from a fake "attacker" VM → Suricata alert

#### S7. Client workstations
- [ ] S7.1 `kyber-ws-lin`: Ubuntu Desktop, joined to FreeIPA (`ipa-client-install`). DHCP+SLAAC. Trusts the FreeIPA CA so it sees REST endpoints as valid TLS.
- [ ] S7.2 `kyber-ws-win`: Windows 10/11. Either join to FreeIPA via the AD-trust path, or stand up a small Samba AD and federate — note the choice in the report. Heterogeneous-OS requirement is satisfied either way.
- [ ] S7.3 **Acceptance**: log in as `bob` on Linux and `alice` on Windows using directory credentials.

#### S8. ipv6-only host
- [ ] S8.1 `kyber-v6host`: Ubuntu, single NIC on `sk07-ipv6only`, no IPv4 address (or DHCPv4 disabled), gets ULA via SLAAC.
- [ ] S8.2 **Acceptance**: `curl -6 https://api.kyber.sk07.lab` works (NPTv6 path); `curl -4 …` fails (no v4 stack); `ip -6 route` shows default via `fe80::…%eth0`.

---

## 3. Phase 2 — Integration (joint, after Phase 1)

- [ ] **I1** DNAT publishing: Person A adds destination NAT on VyOS so that `88.200.24.237:443` → `192.168.7.100:443` (the keepalived VIP) and same for IPv6 (or just route the AAAA directly to `2001:1470:fffd:9a::100`, no NAT needed for v6 — preferred). Add the matching firewall accept rules.
- [ ] **I2** Public DNS: pick `api.kyber.sk07.lab` and `vpn.kyber.sk07.lab` (and optionally `mon.kyber.sk07.lab`, although monitoring usually shouldn't be world-reachable). Either publish A/AAAA records via a real DNS provider, or stand up an external view on app-01 that the lab DNS forwards to.
- [ ] **I3** End-to-end test plan executed and screenshotted. Each item below becomes a paragraph in the report:
   - external user → REST API over HTTPS, all 3 content types
   - internal user → same hostname → split-DNS resolves to private IP
   - VPN user → can reach internal + DMZ
   - ipv6-only host → outbound IPv6 works (NPTv6)
   - kill nginx on app-01 → keepalived fails over → request still served
   - kill etcd leader → cluster re-elects, consumer survives
   - `nmap` from outside → only intended ports open
- [ ] **I4** Final `config.boot` snapshot to `/snapshots/9999-final-config.boot`.
- [ ] **I5** Final network diagram (drawio + PNG) committed.

---

## 4. Technical report (graded — start during Phase 1)

`/report/kyber-report.pdf`, generated from `/report/kyber-report.md`. Required sections:

1. Architecture overview + final network diagram
2. IP plan (v4 + v6 — copy §0)
3. VM inventory and roles (copy §0.6)
4. Per-segment description (purpose, tenants, isolation rationale)
5. VyOS configuration walkthrough (DHCP, DNS, NAT, NPTv6, NTP, VPN)
6. Firewall policy table + per-row rationale
7. REST service: data model, endpoints, content negotiation, auth flow, TLS / HTTP2 (/HTTP3 if done), HA design with failover demo
8. Identity (FreeIPA): schema, groups, integration points (VPN, REST, host login)
9. Monitoring: collected metrics, why each, sample dashboards (screenshots)
10. RAFT/etcd: topology, consumer, failover demo
11. *(Optional)* IDS/IPS demo
12. Test results — every "Acceptance" line above with evidence (screenshot / log / pcap)
13. Lessons learned

---

## 5. Cross-cutting checklist (the requirement-mapping that the grader will use)

- [ ] Users and servers in separate segments — internal vs DMZ
- [ ] Heterogeneous OS — `kyber-ws-lin` (Linux) + `kyber-ws-win` (Windows)
- [ ] Internal `10.7.0.0/24`, DMZ `192.168.7.0/24` — §0.2
- [ ] ipv6-only segment using ULA + NPTv6 — §0.3 + N2
- [ ] DNS, domain, NTP, DHCP, NAT — N3, N4, N5, N1
- [ ] Split DNS (internal vs external view) — N4 + S2.9
- [ ] Servers get DHCP-but-fixed addresses — N3.2, N3.4
- [ ] IPv6: static on VyOS ✓, ≥1 segment SLAAC (internal) ✓, ≥1 segment DHCPv6 (DMZ) ✓
- [ ] REST: 2 related resources, content negotiation in 3 formats, optional auth via LDAP, TLS w/ real certs, persistent DB, HTTP/1.1 + HTTP/2, HA — S2
- [ ] On-prem directory — FreeIPA on `kyber-ldap-01` — S1
- [ ] Firewalls dual-stack with documented policy — N6
- [ ] VPN with optional LDAP auth — N7
- [ ] SNMP + Prometheus/Grafana with short intervals — N8 + S4
- [ ] *(opt)* NetFlow + ntop — N9 + S5
- [ ] *(opt)* IDS/IPS demo — S6
- [ ] RAFT on ≥3 hosts, HA demo — S3
- [ ] Tech report with diagram, services, firewall, configs — §4

---

## 6. Working agreement

- All commits to GitHub. PRs reviewed by the other person.
- VyOS changes: every `commit; save` is followed by a snapshot copy into `/snapshots/`.
- Service VMs: configs live in `/etc/...` on the box but a copy goes to `/services/<name>/` in the repo on every meaningful change.
- Disagreements on scope go in `/decisions.md` with date + rationale.
