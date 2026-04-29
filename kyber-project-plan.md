# kyber (sk07) — Communication Protocols Project Plan

This document is the canonical, consolidated task list for the project. It merges and reconciles two earlier structured drafts, using the original Slovene brief as the source of truth. Tasks are split into a shared bootstrap phase, two parallel workstreams (one per teammate), and a final integration phase.

**Conventions:**
- `[B#]` = Bootstrap (shared) task
- `[N#]` = Networking track (Person A)
- `[S#]` = Services track (Person B)
- `[I#]` = Integration task (joint, after parallel phase)
- "Acceptance" lines define when a task is truly done.
- `(optional)` marks tasks explicitly marked optional in the original brief.

---

## 0. Project Constants (single source of truth)

### 0.1 Identity

| Item | Value |
|---|---|
| Company name | `kyber` |
| Group | `sk07` (group number 7) |
| Internal DNS domain | `kyber.local` |

### 0.2 IPv4 Address Plan

| Segment | Network | Router interface IP | DHCP behaviour |
|---|---|---|---|
| External / WAN | 88.200.24.128/25 | LRK GW = `88.200.24.129`; our address = **`88.200.24.237/25`** | static |
| Internal (users) | **`10.7.0.0/24`** | `10.7.0.1` | dynamic pool (e.g. `10.7.0.100`–`10.7.0.200`) |
| DMZ (servers) | **`192.168.7.0/24`** | `192.168.7.1` | static reservations only (MAC→IP binding) |
| ipv6-only | — no IPv4 — | — | — |

### 0.3 IPv6 Address Plan

The assigned block is `2001:1470:fffd:98::/62`, which splits into exactly 4× /64 subnets. Per the original brief, the first /64 **must** be used for the uplink to LRK's router, the next two are for the dual-stack segments (DMZ and internal), and the last one is the NPTv6 outer prefix for the IPv6-only segment.

| /64 prefix | Use | Router address on this link | Auto-config method |
|---|---|---|---|
| `2001:1470:fffd:98::/64` | External link to LRK | `2001:1470:fffd:98::2/64` (mandatory). GW = `…98::1` | Static |
| `2001:1470:fffd:99::/64` | DMZ segment (dual-stack) | `2001:1470:fffd:99::1/64` | **DHCPv6 stateful** or SLAAC (see note) |
| `2001:1470:fffd:9a::/64` | Internal / users segment (dual-stack) | `2001:1470:fffd:9a::1/64` | **SLAAC** or DHCPv6 (see note) |
| `2001:1470:fffd:9b::/64` | NPTv6 outer prefix for ipv6-only segment | Not assigned to any interface directly | NPTv6 maps to inner ULA |

> **Note on SLAAC vs DHCPv6 assignment:** The original brief requires at least one segment using SLAAC and at least one using DHCPv6. The two structured drafts assigned these differently. Choose one consistent mapping and document it. A reasonable default: **SLAAC on internal** (users get auto-configured addresses) and **DHCPv6 stateful on DMZ** (servers get predictable addresses via DUID reservations). Alternatively you can swap them — just be consistent and satisfy the "at least one of each" requirement.

**IPv6-only segment inner ULA prefix:** Pick a ULA prefix such as `fd07:7::/64` (or `fd47::/64` — the exact choice doesn't matter as long as it's a valid ULA). NPTv6 maps this ↔ `2001:1470:fffd:9b::/64`. SLAAC announces this ULA prefix on eth3 so hosts auto-configure.

### 0.4 ESXi Port Groups & VyOS NIC Mapping

| NIC # | Port group | VyOS interface | Role |
|---|---|---|---|
| 1 | `LRK-public-internet` | `eth0` | WAN (external) |
| 2 | `sk07-internal` | `eth1` | Internal / users |
| 3 | `sk07-dmz` | `eth2` | DMZ / servers |
| 4 | `sk07-ipv6only` | `eth3` | IPv6-only |

### 0.5 VM Inventory (target end-state)

Group services sensibly — don't create a separate VM for each feature, but don't overload one VM either. The following is a recommended layout:

| VM name | OS | Segment (port group) | Roles |
|---|---|---|---|
| `kyber-rtr-01` (existing) | VyOS 1.4.4 | all 4 NICs | Router, firewall, NAT, NPTv6, DHCP/DHCPv6, DNS forwarder, NTP relay, VPN endpoint, SNMP agent |
| `kyber-app-01` | Ubuntu Server LTS | sk07-dmz | nginx (TLS, HTTP/2), REST API instance 1, PostgreSQL primary, etcd node 1, internal authoritative DNS (BIND9 or similar) |
| `kyber-app-02` | Ubuntu Server LTS | sk07-dmz | nginx (TLS, HTTP/2), REST API instance 2, PostgreSQL replica, etcd node 2 |
| `kyber-mon-01` | Ubuntu Server LTS | sk07-dmz | Prometheus, Grafana, snmp_exporter, node_exporter, etcd node 3, *(opt)* ntopng, *(opt)* Suricata |
| `kyber-ldap-01` | Ubuntu Server LTS | sk07-dmz (or sk07-internal) | FreeIPA or OpenLDAP — the on-prem user directory |
| `kyber-ws-lin` | Ubuntu Desktop (or similar) | sk07-internal | End-user Linux client (heterogeneous OS requirement) |
| `kyber-ws-win` | Windows 10/11 | sk07-internal | End-user Windows client (heterogeneous OS requirement) |
| `kyber-v6host` | Ubuntu Server LTS (minimal) | sk07-ipv6only | Demonstrates the IPv6-only + NPTv6 segment |

> **Note:** If resources are tight, you can merge `kyber-ldap-01` into `kyber-app-01`, but FreeIPA generally prefers its own host. The 3 etcd nodes across app-01, app-02, and mon-01 are the minimum for RAFT (≥3 required). The two client workstations (`kyber-ws-lin` and `kyber-ws-win`) satisfy the optional heterogeneous OS requirement.

### 0.6 GitHub Repo Layout

```
/README.md                  ← Project overview + §0 constants
/network/
   README.md                ← Topology, diagrams, VyOS rationale
   firewall-policy.md       ← Firewall rule matrix + rationale
   diagrams/                ← drawio/excalidraw sources + PNG exports
/snapshots/
   0000-baseline-config.boot
   0001-<topic>-config.boot
   ...
/services/
   rest-api/README.md
   ldap/README.md
   monitoring/README.md
   etcd/README.md
   dns/README.md
   vpn/README.md
   ipv6/README.md
/report/
   kyber-report.md
   kyber-report.pdf
```

---

## 1. Phase B — Bootstrap (BOTH teammates, before splitting)

These tasks must be completed before either workstream can proceed independently. Do them together (screen-share or pair session).

- [ ] **B1** Create the GitHub repo with the layout from §0.6. Commit §0 into `/README.md`.

- [ ] **B2** SSH to the existing VyOS VM. Run `show configuration commands` and export `/config/config.boot` (via `scp`) into `/snapshots/0000-baseline-config.boot`.

- [ ] **B3** Set system-level parameters on VyOS:
  - `system host-name kyber-rtr-01`
  - `system domain-name kyber.local`
  - Timezone: `Europe/Ljubljana`
  - NTP servers: e.g. `0.si.pool.ntp.org`, `1.si.pool.ntp.org`, or `ntp.arnes.si`
  - Create an admin user with SSH public-key authentication

- [ ] **B4** Configure all four interfaces with the IPv4 + IPv6 addresses from §0.2 and §0.3:
  - `eth0` → IPv4 `88.200.24.237/25`, IPv6 `2001:1470:fffd:98::2/64`
  - `eth1` → IPv4 `10.7.0.1/24`, IPv6 from the internal /64
  - `eth2` → IPv4 `192.168.7.1/24`, IPv6 from the DMZ /64
  - `eth3` → ULA address only (e.g. `fd07:7::1/64`), **no IPv4**
  - Default routes: IPv4 → `88.200.24.129`, IPv6 → `2001:1470:fffd:98::1`
  - Enable IPv4 and IPv6 forwarding
  - **Do not configure firewall rules yet** (leave default-allow for now)

- [ ] **B5** Verify basic connectivity from the router:
  - `ping 8.8.8.8` ✓
  - `ping 88.200.24.129` ✓
  - `ping6 2001:1470:fffd:98::1` ✓
  - `ping6 2001:4860:4860::8888` ✓

- [ ] **B6** Decide and document technology choices in `/README.md`:
  - REST framework (e.g. Python/FastAPI, Node.js/Express, Java/Spring)
  - Database (e.g. PostgreSQL, MySQL, MongoDB)
  - LDAP solution (FreeIPA or OpenLDAP)
  - VPN solution (WireGuard on VyOS, or OpenVPN on a separate VM — note that WireGuard is key-based so LDAP password auth requires scripting or switching to OpenVPN)
  - Monitoring stack (Prometheus + Grafana recommended)
  - IDS/IPS if doing the optional part (e.g. Suricata, Snort)

- [ ] **B7** Create the remaining VMs as bare OS installs, attached to the correct port groups. Give them temporary static IPs (no services yet) so both teammates can SSH in.

- [ ] **B8** Commit `/snapshots/0001-after-bootstrap-config.boot`.

> ✅ **Gate:** When B1–B8 are green, split into Track N and Track S.

---

## 2. Phase 1 — Parallel Work

Person A and Person B work independently. The only synchronization points are: (a) Person B's services need to be published externally via DNAT — Person A handles that in Phase 2 (I1), and (b) Person B needs to know gateway/DNS/NTP IPs, which are already fixed in §0.

---

### Track N — Networking (Person A) — owns `kyber-rtr-01`

#### N1. NAT (source masquerade)
- [ ] N1.1 Configure NAT44 source masquerade on `eth0` for outbound traffic from `10.7.0.0/24` and `192.168.7.0/24`.
- [ ] N1.2 **Acceptance:** From a VM on the internal or DMZ segment, `curl ifconfig.me` returns `88.200.24.237`.

#### N2. NPTv6 (IPv6-to-IPv6 Network Prefix Translation)
- [ ] N2.1 Configure source NPTv6 rule translating inner ULA prefix (e.g. `fd07:7::/64`) → outer `2001:1470:fffd:9b::/64` on egress through `eth0`. VyOS uses `nat66` commands (supported in VyOS ≥1.2 per the original brief).
- [ ] N2.2 Configure the corresponding destination NPTv6 rule for the reverse path (incoming `2001:1470:fffd:9b::/64` → inner ULA).
- [ ] N2.3 **Acceptance:** From `kyber-v6host` (which has only a ULA address), `ping6 ipv6.google.com` works. Running `tcpdump -i eth0 ip6` on the router shows the source rewritten to `2001:1470:fffd:9b::…`.

#### N3. DHCP / DHCPv6 / SLAAC / Router Advertisements
- [ ] N3.1 **DHCPv4 on eth1 (internal):** Pool e.g. `10.7.0.100`–`10.7.0.200`, DNS server = `10.7.0.1` (or the internal DNS once it exists), domain = your chosen domain, gateway = `10.7.0.1`.
- [ ] N3.2 **DHCPv4 on eth2 (DMZ):** Static MAC→IP mappings for every server VM (e.g. `app-01 = 192.168.7.10`, `app-02 = 192.168.7.11`, `mon-01 = 192.168.7.20`, `ldap-01 = 192.168.7.30`). Optionally include a small dynamic pool for ad-hoc test VMs. **Key requirement from the original brief:** servers must always get the same IP, but the IP must still be assigned via DHCP (i.e. static DHCP reservation, not a manual static config on the server).
- [ ] N3.3 **IPv6 — SLAAC segment:** On whichever segment you designated for SLAAC (e.g. eth1/internal), configure `router-advert` with `autonomous-flag true`, `on-link-flag true`, and the appropriate /64 prefix. Also advertise DNS server and domain via RA options.
- [ ] N3.4 **IPv6 — DHCPv6 segment:** On whichever segment you designated for DHCPv6 (e.g. eth2/DMZ), configure stateful DHCPv6 with per-DUID reservations matching the IPv4 static mappings. Set RA flags: `managed-flag true`, `other-config-flag true`, `autonomous-flag false`.
- [ ] N3.5 **IPv6 — ipv6-only segment:** Configure SLAAC on eth3 announcing the ULA prefix (e.g. `fd07:7::/64`).
- [ ] N3.6 **Acceptance:** A freshly-booted Ubuntu VM on the internal segment gets a DHCPv4 lease + IPv6 address via the chosen method. A VM on the DMZ segment with a reserved MAC gets its predictable IPv4 + IPv6 addresses. A VM on the ipv6-only segment gets only a ULA address, no IPv4.

#### N4. DNS Forwarding + Split DNS (VyOS side)
- [ ] N4.1 Configure DNS forwarding on VyOS: listen on `10.7.0.1` and `192.168.7.1`, forward general queries to public upstream resolvers (e.g. `1.1.1.1`, `8.8.8.8`, `2001:4860:4860::8888`).
- [ ] N4.2 For the internal domain zone (e.g. `kyber.local`): forward queries to the internal authoritative DNS server (set up by Person B in S2) so that internal clients resolve internal hostnames to private IPs.
- [ ] N4.3 **Split-DNS effect:** When an internal client queries `api.kyber.local`, it gets the private DMZ address (e.g. `192.168.7.10`). When an external client queries the same name (if public DNS is configured), it gets the public IP `88.200.24.237`. This satisfies the original brief's split-DNS requirement.
- [ ] N4.4 **Acceptance:** From `kyber-ws-lin`, `dig api.kyber.local` returns the private IP. From an external host (or over VPN with appropriate DNS), the resolution returns `88.200.24.237` (or fails gracefully if no public DNS is set up).

#### N5. NTP Relay
- [ ] N5.1 VyOS is already configured as an NTP client (B3). Additionally, enable it to serve NTP to internal and DMZ clients: `set service ntp listen-address 10.7.0.1` and `192.168.7.1`.
- [ ] N5.2 **Acceptance:** `chronyc sources` (or `ntpq -p`) on a DMZ server shows VyOS as a sync peer.

#### N6. Firewall (dual-stack, zone-based)

First, write the firewall policy as a human-readable document (`/network/firewall-policy.md`) explaining the rationale for every rule. Then encode the rules in VyOS.

**Zones:** `WAN` (eth0), `INTERNAL` (eth1), `DMZ` (eth2), `V6ONLY` (eth3), `LOCAL` (the router itself), and optionally `VPN` (WireGuard/OpenVPN interface).

**Minimum required rule matrix** (from the original brief — "think about and document what rules should apply within your network, for inbound access, for outbound traffic, which services are exposed externally, which are internal-only, which are for customers vs. administrators"):

| From → To | Policy |
|---|---|
| any → any | Allow established/related connections (stateful tracking) |
| WAN → DMZ | Allow inbound only to explicitly exposed service ports (e.g. TCP/443 for HTTPS, VPN port). Drop everything else |
| WAN → INTERNAL | Drop all (internal network must not be reachable from the internet) |
| WAN → LOCAL | Allow ICMP echo (rate-limited), VPN port. Drop all else (no raw SSH from WAN) |
| INTERNAL → WAN | Allow all outbound (NAT handles return traffic). Users must have internet access (original brief requirement) |
| INTERNAL → DMZ | Allow access to published service ports + SSH for administration from designated hosts |
| INTERNAL → LOCAL | Allow SSH (management), DNS, NTP, DHCP |
| DMZ → INTERNAL | Deny new connections (servers cannot initiate connections to internal users) |
| DMZ → WAN | Allow outbound for updates (apt/HTTPS), NTP. Restrict general outbound |
| DMZ → LOCAL | Allow DNS, NTP, SNMP responses |
| V6ONLY → WAN | Allow all IPv6 outbound |
| WAN → V6ONLY | Drop all new inbound |
| VPN → INTERNAL/DMZ | Allow as needed for remote workers (SSH, HTTPS, RDP, etc.) |
| LOCAL → any | Allow all (router-initiated traffic) |

- [ ] N6.1 Encode the full matrix as both IPv4 **and** IPv6 firewall rules. The original brief explicitly states: "Don't forget that your network is IPv4 and IPv6 (dual stack)." VyOS has separate firewall tables for IPv4 and IPv6.
- [ ] N6.2 **Acceptance:** From outside, `nmap -Pn 88.200.24.237` and `nmap -6 …` show only intentionally exposed ports. From the DMZ, scanning the internal subnet is fully filtered.

#### N7. VPN
- [ ] N7.1 Deploy a VPN server. Options: WireGuard on VyOS (natively supported in 1.4.x) or OpenVPN on a separate lightweight VM. If LDAP password authentication is required (optional requirement from the original brief), OpenVPN with `openvpn-auth-ldap` is the simpler path, since WireGuard is key-only.
- [ ] N7.2 VPN must allow remote users to reach both `10.7.0.0/24` (internal) and `192.168.7.0/24` (DMZ). Push routes and DNS settings to clients.
- [ ] N7.3 Expose the VPN port through the firewall (add matching rule in N6).
- [ ] N7.4 **(optional)** Authenticate VPN users against the LDAP directory (S1). For WireGuard, this means scripting key generation gated by LDAP group membership. For OpenVPN, use the LDAP auth plugin directly.
- [ ] N7.5 Provide a sample client configuration file in the repo (`/services/vpn/`).
- [ ] N7.6 **Acceptance:** From a laptop off-LAN, connect to VPN, receive tunnel addresses, successfully SSH to an internal host, and `dig api.kyber.local` returns the internal IP.

#### N8. SNMP Agent on VyOS
- [ ] N8.1 Enable SNMP on VyOS. Use SNMPv2c with a read-only community string (e.g. `kyber-ro`), or preferably SNMPv3 with authPriv for better security.
- [ ] N8.2 Restrict SNMP access to only the monitoring server's IP (e.g. `192.168.7.20`).
- [ ] N8.3 **Acceptance:** `snmpwalk` from the monitoring VM returns interface counters and system info.

#### N9. (optional) NetFlow / sFlow Export
- [ ] N9.1 Configure NetFlow (v9 or IPFIX) or sFlow export from VyOS to the monitoring VM (e.g. UDP/2055).

#### N10. Snapshots & Documentation (continuous)
- [ ] After every meaningful change, save `config.boot` as `/snapshots/NNNN-<topic>-config.boot` and commit.
- [ ] Keep `/network/README.md` current with the topology diagram.

---

### Track S — Services (Person B) — owns the application VMs

These tasks require only that Phase B is done (VMs reachable, gateway up, DNS/NTP available). They do not block on the firewall or VPN being configured.

#### S1. LDAP / User Directory

The original brief says: "Set up an on-prem user directory. Can be AD (Microsoft Active Directory) or a Linux LDAP server such as OpenLDAP or FreeIPA. Create a few test users that you'll reuse in other parts of the system (VPN, REST API auth, ...)."

- [ ] S1.1 Deploy a Linux VM (or use `kyber-ldap-01`) on the DMZ or internal segment. Install your chosen directory service (FreeIPA recommended — gives LDAP + Kerberos + internal CA in one package; OpenLDAP is also fine).
- [ ] S1.2 Create the directory tree for your domain (e.g. `dc=kyber,dc=local`).
- [ ] S1.3 Create groups: at minimum `users`, `admins`, `vpn-users`, `api-writers` (or equivalent).
- [ ] S1.4 Create at least 3–4 test users with varying group memberships, e.g.:
  - `alice` ∈ admins, vpn-users
  - `bob` ∈ users, vpn-users
  - `carol` ∈ api-writers
  - `dave` ∈ users
- [ ] S1.5 If using FreeIPA, export the CA certificate — both tracks will need it for TLS trust.
- [ ] S1.6 **Acceptance:** `ldapsearch` against the directory with a test user's credentials succeeds and shows correct group memberships.
- [ ] S1.7 Document the LDAP schema, user DNs, and groups in `/services/ldap/README.md`.

#### S2. Internal Authoritative DNS

The original brief requires split DNS: an internal DNS server that returns private IPs for internal names, while external queries (if public DNS is configured) return the public IP.

- [ ] S2.1 Deploy a DNS server (BIND9 or Unbound) — can run on `kyber-app-01` or a dedicated VM.
- [ ] S2.2 Configure it as the authoritative nameserver for the internal domain (e.g. `kyber.local`).
- [ ] S2.3 Register A and AAAA records for every server in the DMZ (REST API, LDAP, monitoring, etc.) pointing to their private IPs.
- [ ] S2.4 Coordinate with Person A: VyOS DNS forwarding (N4.2) will forward internal domain queries to this server.
- [ ] S2.5 **(optional)** Configure an "external" view that returns the public IP `88.200.24.237` for the same hostnames, to fully implement split DNS for external clients.
- [ ] S2.6 **Acceptance:** From an internal client, `dig api.kyber.local` returns the private DMZ IP.
- [ ] S2.7 Document the zone file and records in `/services/dns/README.md`.

#### S3. REST API Service

The original brief specifies: two related resources, content negotiation in at least 3 formats (JSON, XML, + one more), TLS with real certificates, persistent database, HTTP/1.1 + HTTP/2, high availability. Optional: authenticated endpoints via LDAP, HTTP/3, GraphQL.

- [ ] S3.1 **Database:** Deploy PostgreSQL (or your chosen DB) on `kyber-app-01`. Set up the schema for two related resources (e.g. `customers` and `orders` with a foreign key relationship). **(optional)** Configure streaming replication to `kyber-app-02` for HA.
- [ ] S3.2 **REST API application:** Implement full CRUD for both resources using your chosen framework (FastAPI, Express, Spring, etc.).
- [ ] S3.3 **Content negotiation:** The API must respond differently based on the `Accept` header:
  - `application/json` → JSON response
  - `application/xml` → XML response
  - At least one more format: `text/html` (rendered table), `text/csv`, `text/plain`, etc.
- [ ] S3.4 **Persistence:** Data must survive server restarts (stored in the database, not in-memory).
- [ ] S3.5 **TLS:** Generate proper certificates (via FreeIPA's CA, a self-signed CA, or Let's Encrypt if you have a public domain). Serve the API exclusively over HTTPS. Do not use throwaway self-signed certs — the original brief says "real certificates."
- [ ] S3.6 **HTTP/2:** Configure the web server or reverse proxy (nginx recommended) with `listen 443 ssl http2;` so the API is accessible over HTTP/2 in addition to HTTP/1.1.
- [ ] S3.7 **High availability:** Run at least two instances of the API (on `kyber-app-01` and `kyber-app-02`) behind a load balancer (nginx upstream, HAProxy, or keepalived with a VIP). Killing one instance must not take down the service.
- [ ] S3.8 **(optional) Authenticated endpoints:** Protect at least one write operation (e.g. `POST`, `PUT`, or `DELETE`) so only users authenticated against the LDAP directory can call it. Verify the user belongs to a specific group (e.g. `api-writers`). Use HTTP Basic Auth or Bearer tokens (e.g. JWT issued after LDAP bind). `GET` requests remain public.
- [ ] S3.9 **IPv6:** The API must be accessible over IPv6 as well. Bind nginx to both the IPv4 and IPv6 addresses of the DMZ VM. The original brief states: "Services you offer to external users must also be accessible over IPv6 where sensible."
- [ ] S3.10 **(optional) HTTP/3:** Enable QUIC support in nginx ≥1.25 (`listen 443 quic reuseport;` + `Alt-Svc` header).
- [ ] S3.11 **(optional) GraphQL:** Mirror the same resources via a GraphQL endpoint (e.g. using Strawberry for FastAPI, Apollo for Node.js, etc.).
- [ ] S3.12 **Acceptance:**
  - `curl -H 'Accept: application/json' https://api.kyber.local/customers` → JSON
  - Same with `application/xml` → XML
  - Same with `text/html` → rendered HTML
  - `curl -X POST .../orders` without auth token → `401`; with authorized user's token → `201`; with unauthorized user's token → `403` (if optional auth is implemented)
  - Browser dev tools confirm protocol is `h2`
  - Killing nginx/API on app-01 → traffic still served via app-02 within seconds
- [ ] S3.13 Document endpoints, request/response formats, auth flow, and HA design in `/services/rest-api/README.md`.

#### S4. RAFT Cluster

The original brief says: "Set up a service using the RAFT protocol on at least 3 machines. Can be anything that uses RAFT, e.g. etcd with a web application or similar. The service must be highly available."

- [ ] S4.1 Install etcd on 3 nodes: `kyber-app-01`, `kyber-app-02`, and `kyber-mon-01`. Form a cluster. **(optional)** Use TLS for peer and client traffic, with certs from the internal CA.
- [ ] S4.2 Build a small consumer application that demonstrably uses the etcd cluster. Options:
  - Use etcd as a key-value backend for the REST API (e.g. feature flags, config store)
  - Build a minimal "leader status" web page that reads the current leader from etcd
  - Any other application that reads/writes to the etcd cluster
- [ ] S4.3 **Acceptance:** `etcdctl endpoint status --cluster -w table` shows all 3 members with a leader. `systemctl stop etcd` on the leader → a new leader is elected within seconds → the consumer application continues working.
- [ ] S4.4 Document the cluster setup and HA demonstration in `/services/etcd/README.md`.

#### S5. Monitoring (Prometheus + Grafana)

The original brief says: "Configure SNMP for event logging (at least one source, e.g. traffic metrics, web/application server, CPU, memory). Data should be visible graphically (Prometheus + Grafana or similar). Set appropriately short SNMP polling intervals."

- [ ] S5.1 Deploy Prometheus, Grafana, and `snmp_exporter` on `kyber-mon-01`.
- [ ] S5.2 Configure Prometheus to scrape at short intervals (≤30s, as the brief requests short intervals):
  - `snmp_exporter` targeting VyOS (interface traffic counters in/out on eth0–eth3, CPU, memory)
  - `node_exporter` on all Linux VMs (CPU, RAM, disk)
  - **(optional)** `nginx-prometheus-exporter` on app-01/app-02
  - **(optional)** `postgres_exporter` on app-01/app-02
  - **(optional)** etcd native `/metrics` endpoint
- [ ] S5.3 Build Grafana dashboards showing at minimum:
  - WAN traffic over time / per-interface throughput on VyOS
  - CPU and memory of at least one server
  - **(optional)** REST API request rate, error rate, latency
  - **(optional)** etcd cluster status
- [ ] S5.4 Expose Grafana on HTTPS (behind nginx + TLS). Coordinate with Person A to allow access through the firewall if needed.
- [ ] S5.5 **Acceptance:** Dashboards are visible and show live data. Generating traffic (e.g. `apt update`, `curl` loops) is reflected in the graphs.
- [ ] S5.6 Document in `/services/monitoring/README.md`.

#### S6. (optional) NetFlow / sFlow Analysis
- [ ] S6.1 Install ntopng (or Cacti with NetFlow plugin) on `kyber-mon-01`, listening on the port that matches N9's export.
- [ ] S6.2 Generate traffic (`iperf3`, large downloads). Capture screenshots of top-talkers / flow analysis.

#### S7. (optional) IDS/IPS
- [ ] S7.1 Install Suricata (or Snort) on `kyber-mon-01` or on each app VM.
- [ ] S7.2 Demonstrate detection of: `nmap` scan from outside, SSH brute-force attempt (e.g. via `hydra`), or similar.
- [ ] S7.3 Note: if ESXi security policy doesn't allow promiscuous mode on the vSwitch, run IDS locally on each VM's own NIC and document this limitation.

#### S8. IPv6-only Segment Verification
- [ ] S8.1 Deploy `kyber-v6host` on the `sk07-ipv6only` port group. Single NIC, no IPv4 address. It should auto-configure a ULA address via SLAAC (configured by Person A in N3.5).
- [ ] S8.2 **Acceptance:** `curl -6 https://api.kyber.local` works (traffic goes through NPTv6). `curl -4 …` fails (no IPv4 stack). `ip -6 route` shows a default route via the link-local address of VyOS eth3.
- [ ] S8.3 Document in `/services/ipv6/README.md`.

#### S9. Client Workstations (heterogeneous OS)
- [ ] S9.1 `kyber-ws-lin`: Ubuntu Desktop on sk07-internal. Gets its IP via DHCP + IPv6 via SLAAC/DHCPv6 (depending on your choice). **(optional)** Join to FreeIPA via `ipa-client-install`. Trust the internal CA so HTTPS endpoints validate.
- [ ] S9.2 `kyber-ws-win`: Windows 10/11 on sk07-internal. **(optional)** Join to the directory (via AD trust or Samba). This satisfies the heterogeneous OS requirement.
- [ ] S9.3 **Acceptance:** Both clients can browse the internet, reach DMZ services, and resolve internal DNS names.

---

## 3. Phase 2 — Integration (joint, after Phase 1)

Once both tracks are substantially complete, come together to wire everything end-to-end.

- [ ] **I1 — DNAT / Port Forwarding:** Person A adds destination NAT on VyOS so that traffic to `88.200.24.237:443` is forwarded to the internal load-balanced VIP (e.g. `192.168.7.100:443`). For IPv6, routing the AAAA record directly to the DMZ address is preferred (no NAT needed for v6). Add matching firewall accept rules.

- [ ] **I2 — Public DNS (if applicable):** If you have a real domain name, publish A/AAAA records. Otherwise, document that external access uses the raw IP and note how split-DNS would work with a real domain.

- [ ] **I3 — End-to-end test plan.** Execute and screenshot each test; each becomes a paragraph in the report:
  - External user → REST API over HTTPS, test all 3 content-negotiation formats
  - Internal user → same hostname → split-DNS resolves to private IP
  - VPN user → can reach internal + DMZ subnets
  - IPv6-only host → outbound IPv6 works via NPTv6
  - Kill one REST API instance → HA failover works, requests still served
  - Kill etcd leader → cluster re-elects, consumer survives
  - `nmap` from outside → only intentionally exposed ports are open
  - Firewall blocks: DMZ cannot reach internal, WAN cannot reach internal

- [ ] **I4 — Final config.boot snapshot** → `/snapshots/9999-final-config.boot`.

- [ ] **I5 — Final network diagram** (drawio/excalidraw + PNG export) committed to `/network/diagrams/`.

---

## 4. Technical Report (graded deliverable — start writing during Phase 1)

Output: `/report/kyber-report.md` → export to `/report/kyber-report.pdf`. Both teammates contribute. The original brief says: "Write it as technical documentation — think about what you'd want documented if you arrived at this company and had to take over from the colleague who set it all up."

**Required sections:**

1. **Architecture overview** — final network diagram (logical + physical), all VMs, which vSwitch each connects to, which services each runs
2. **IP address allocation table** — every interface, its IPv4 address, IPv6 address, subnet, and role
3. **VM inventory and roles** — table from §0.5 updated with final state
4. **Per-segment description** — purpose of each segment, what's connected, isolation rationale
5. **VyOS configuration walkthrough** — DHCP, DHCPv6, SLAAC, DNS forwarding, NAT, NPTv6, NTP, VPN — explain each setting and why
6. **Firewall policy** — full rule table with zone-pair, protocol/port, and written rationale per rule
7. **DNS** — zone file listing, split-DNS explanation
8. **REST API** — data model, endpoints, content negotiation, auth flow, TLS/HTTP2 setup, HA design with failover demo
9. **LDAP / user directory** — schema, directory tree, groups, integration points (VPN, REST, host login)
10. **Monitoring** — collected metrics, polling intervals, why each metric matters, sample dashboard screenshots
11. **RAFT / etcd** — topology, consumer application, failover demonstration
12. **VPN** — server config, client setup instructions, sample client config
13. **(optional)** IDS/IPS demo results
14. **(optional)** NetFlow/sFlow analysis results
15. **Test results** — every "Acceptance" item from this document, with evidence (screenshot, log output, or packet capture)
16. **Lessons learned**

---

## 5. Requirement Cross-Check

Use this checklist to verify you've covered every requirement from the original brief before submission:

- [ ] Users and servers in separate segments (internal vs. DMZ)
- [ ] (optional) Heterogeneous OS — at least one Windows and one Linux client
- [ ] Internal segment = `10.7.0.0/24`, DMZ = `192.168.7.0/24`
- [ ] IPv6-only segment using ULA + NPTv6 (RFC 6296, VyOS ≥1.2)
- [ ] DNS, domain name, NTP, DHCP, NAT all configured
- [ ] Split DNS (internal returns private IPs, external returns public IP)
- [ ] Servers get fixed IPs via DHCP (static reservations, not manual config)
- [ ] Users have internet access
- [ ] IPv6 configured: static on VyOS ✓, ≥1 segment SLAAC ✓, ≥1 segment DHCPv6 ✓
- [ ] REST API: 2 related resources, content negotiation (JSON + XML + 1 more), TLS with real certs, persistent DB, HTTP/1.1 + HTTP/2, high availability
- [ ] (optional) REST auth: at least one write operation protected via LDAP
- [ ] (optional) HTTP/3
- [ ] (optional) GraphQL
- [ ] On-prem user directory (AD, OpenLDAP, or FreeIPA) with test users
- [ ] Firewall: zone-based, dual-stack, documented with rationale
- [ ] VPN: remote access to internal network, secure
- [ ] (optional) VPN auth against LDAP
- [ ] SNMP + graphical monitoring (Prometheus/Grafana or equivalent), short polling intervals
- [ ] (optional) NetFlow/sFlow + analyzer (ntopng, Cacti plugin, etc.)
- [ ] (optional) IDS/IPS demonstration (Suricata, Snort, etc.)
- [ ] RAFT on ≥3 nodes, HA demonstrated
- [ ] Technical report with network diagram, all services documented, firewall rules, configs

---

## 6. Working Agreement

- All work committed to GitHub. PRs reviewed by the other person before merging.
- VyOS changes: every `commit; save` is followed by a `config.boot` snapshot copied into `/snapshots/`.
- Service VM configs: live in `/etc/…` on the box, but a copy goes to `/services/<name>/` in the repo on every meaningful change.
- Disagreements on scope or technical choices recorded in a `/decisions.md` with date and rationale.
