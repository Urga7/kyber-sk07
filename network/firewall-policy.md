# kyber (sk07) — Firewall Policy

This document defines the zone-based firewall policy for `kyber-rtr` (VyOS 1.4.4).
Rules apply to **both IPv4 and IPv6** unless noted. VyOS maintains separate rule
tables for each stack (`firewall` for IPv4, `firewall6` for IPv6 — same policy,
encoded twice).

---

## Zones

| Zone     | Interface(s)        | Subnet(s)                                              |
|----------|---------------------|--------------------------------------------------------|
| WAN      | eth0                | 88.200.24.237/25, 2001:1470:fffd:98::2/64              |
| INTERNAL | eth1                | 10.7.0.0/24, 2001:1470:fffd:9a::/64                   |
| DMZ      | eth2                | 192.168.7.0/24, 2001:1470:fffd:99::/64                |
| V6ONLY   | eth3                | fd07:1:1:1::/64 (ULA, NPTv6 outer: 2001:1470:fffd:9b::/64) |
| LOCAL    | (router itself)     | all router-owned addresses                             |
| VPN      | wg0 (once deployed) | 10.7.99.0/24 (IPv4 tunnel), fd07:99::/64 (IPv6 ULA tunnel, TBD in N7) |

**Default policy for all zone pairs not listed below: DROP (silent).**

### Intentionally unlisted pairs (default-drop, by design)

| Pair | Reason |
|------|--------|
| V6ONLY → DMZ | IPv6-only hosts communicate outbound via NPTv6 to WAN only. They have no reason to reach DMZ servers directly. Default-drop is intentional. |
| V6ONLY → INTERNAL | Same rationale. The ipv6-only segment is isolated; no lateral access to other LAN segments. |
| DMZ → DMZ | Intra-DMZ traffic (etcd, PostgreSQL replication, app→ldap) is L2-switched on eth2 and never traverses `kyber-rtr`, so the firewall never sees it. No configuration required. |

---

## Canonical rule-number layout

Every zone-pair ruleset uses the same numbering scheme so the encoding order is
unambiguous:

| Range | Purpose |
|-------|---------|
| **1–9**   | Anti-spoof / bogon drops (WAN-inbound rulesets) and analogous defensive same-source drops (e.g. NPTv6 safety on V6ONLY → WAN). Drop obviously-forged packets *before* consulting connection state. |
| **10–11** | Stateful baseline — established/related accept, then invalid drop. |
| **20+**   | Zone-pair service rules. |
| **999**   | Catch-all drop (logged where security-relevant, see *Logging policy*). |

In rulesets that don't need anti-spoofing (everything except WAN-inbound and
V6ONLY → WAN), the 1–9 range is simply empty; the gap is reserved by convention.

### Baseline: stateful connection tracking

Conceptually these two rules belong in **every** zone-pair ruleset at the same
numbers. On VyOS 1.4.4 they are encoded **once** as a global state-policy
(`firewall global-options state-policy`) rather than repeated per ruleset — same
effect, applied across all chains, and it keeps rule numbers 10/11 free.

| # | Match | Action | Rationale |
|---|-------|--------|-----------|
| 10 | state established,related | accept | Return traffic for already-approved sessions. Without this, approved outbound flows would never receive replies. |
| 11 | state invalid | drop | Packets that don't belong to any tracked session and aren't valid new connections are noise or attack fragments. |

---

## Zone-pair rules

### WAN → LOCAL
Traffic destined for the router itself, arriving from the internet.

| # | Proto | Port/Type    | Action | Rationale |
|---|-------|--------------|--------|-----------|
| 20 | ICMPv4 | echo-request (rate-limit 10/s) | accept | Reachability probing. Rate-limited to blunt amplification. |
| 21 | ICMPv6 | echo-request (rate-limit 10/s) | accept | Same for IPv6. Also required for NDP/path-MTU to function. |
| 30 | UDP   | 51820 (WireGuard) | accept | VPN endpoint (N7). Only intentionally exposed management port on the router. |
| 999 | any   | any          | drop + log | Everything else. No raw SSH from WAN — management is VPN-only. |

> **Temporary exception (pre-N7):** until WireGuard is deployed, the router is
> only reachable for management over WAN SSH. The encoding (06) therefore adds a
> **temporary `tcp/22` accept** here, source-restricted to the admin host where
> possible, and **removed once N7 is live**. Without it, committing the policy
> would lock out the remote admin session.

### WAN → DMZ
Inbound traffic from the internet reaching DMZ servers. IPv4 arrives via DNAT (I1);
IPv6 is routed directly to DMZ addresses.

| # | Proto | Destination port | Action | Rationale |
|---|-------|-----------------|--------|-----------|
| 20 | TCP   | 443 (HTTPS)     | accept | REST API (app-01/02 via DNAT VIP 192.168.7.100 for v4; direct to 2001:1470:fffd:99::10 for v6). S3.5, S3.6, I1. |
| 21 | UDP   | 443 (HTTP/3)    | accept | QUIC/HTTP3 (optional S3.10). Same destination as above. |
| 999 | any  | any             | drop + log | All other inbound DMZ access is prohibited. FreeIPA, Grafana, LDAP, Prometheus are internal-only. |

### WAN → INTERNAL
| # | Action | Rationale |
|---|--------|-----------|
| 999 | drop + log | Internal workstation network must never be reachable from the internet. No exceptions. |

### WAN → V6ONLY
| # | Action | Rationale |
|---|--------|-----------|
| 999 | drop | IPv6-only segment hosts have ULA addresses; NPTv6 outer prefix is not a reachable destination for new inbound connections. |

---

### INTERNAL → WAN
| # | Action | Rationale |
|---|--------|-----------|
| 20 | accept | Users must have unrestricted internet access (project brief requirement). NAT masquerade (N1) handles source translation. |

### INTERNAL → DMZ
Traffic from internal workstations to DMZ servers.

| # | Proto | Port | Action | Rationale |
|---|-------|------|--------|-----------|
| 20 | TCP   | 443  | accept | Workstations browse the REST API (S3) and Grafana dashboard (S5) over HTTPS. |
| 21 | TCP   | 22   | accept | SSH administration to DMZ servers. Restricted to this direction only (DMZ cannot SSH back into INTERNAL). |
| 30 | TCP+UDP | 53 | accept | DNS queries to FreeIPA (192.168.7.30). VyOS forwards kyber.local queries there; direct access from workstations as a fallback. |
| 31 | TCP   | 636  | accept | LDAPS — workstations and app servers authenticate against FreeIPA (S1.6, S3.8). |
| 32 | TCP+UDP | 88 | accept | Kerberos — required if workstations join FreeIPA domain (optional S9.1). |
| 33 | TCP+UDP | 464 | accept | kpasswd — Kerberos password change (optional S9.1). |
| 34 | TCP   | 389  | drop   | Plain LDAP not permitted. LDAPS (636) must be used for all directory traffic. |
| 999 | any  | any  | drop   | Everything else. Workstations have no business reaching Prometheus (9090), etcd (2379), PostgreSQL (5432), or SNMP (161) directly. |

### INTERNAL → LOCAL
Traffic from internal clients to the router itself.

| # | Proto | Port | Action | Rationale |
|---|-------|------|--------|-----------|
| 20 | TCP   | 22   | accept | SSH management of the router from the internal network. |
| 30 | UDP+TCP | 53 | accept | DNS forwarding service (N4). Workstations use VyOS as their resolver. |
| 31 | UDP   | 123  | accept | NTP relay (N5). Internal clients sync to VyOS. |
| 32 | UDP   | 67,68 | accept | DHCPv4 (N3). Broadcast-based; VyOS is the DHCP server on eth1. |
| 33 | UDP   | 546,547 | accept | DHCPv6 (N3). |
| 34 | ICMPv4+ICMPv6 | echo-request | accept | Clients should be able to ping the gateway. |
| 999 | any  | any  | drop   | No direct access to SNMP, WireGuard port, or any other router service from clients. |

---

### DMZ → WAN
Traffic from DMZ servers to the internet.

| # | Proto | Port | Action | Rationale |
|---|-------|------|--------|-----------|
| 20 | TCP   | 80   | accept | apt package updates over HTTP (redirect to HTTPS is server-side). |
| 21 | TCP   | 443  | accept | apt over HTTPS, pip, OS updates, FreeIPA CRL/OCSP, certmonger renewal, external API calls. |
| 22 | UDP   | 123  | accept | NTP fallback directly to upstream pool, in case VyOS relay is unavailable. |
| 23 | ICMPv4+ICMPv6 | echo-request | accept | Servers need to be able to ping the internet for diagnostics. |
| 999 | any  | any  | drop   | DMZ servers have no legitimate reason to initiate other outbound connections. Restricting this limits blast radius if a server is compromised. |

### DMZ → INTERNAL
| # | Action | Rationale |
|---|--------|-----------|
| 999 | drop + log | Servers must never initiate connections to internal workstations. If a DMZ server is compromised, this prevents lateral movement to the user segment. Logged for forensic visibility. |

### DMZ → LOCAL
Traffic from DMZ servers to the router itself.

| # | Proto | Port | Action | Rationale |
|---|-------|------|--------|-----------|
| 20 | UDP+TCP | 53 | accept | DNS forwarding (N4). DMZ servers use VyOS as their resolver. |
| 21 | UDP   | 123  | accept | NTP relay (N5). DMZ servers sync to VyOS. |
| 22 | UDP   | 68   | accept | DHCPv4 renew traffic (N3). |
| 23 | UDP   | 547  | accept | DHCPv6 (N3). |
| 24 | UDP   | 161  | accept | SNMP polling: `kyber-mon` (192.168.7.20 / 2001:1470:fffd:99::20) queries VyOS for interface counters and system metrics (N8, S5). **Source restricted to 192.168.7.20 and 2001:1470:fffd:99::20 only.** |
| 25 | TCP   | 22   | accept | SSH management of the router from the DMZ (admin access). |
| 26 | ICMPv4+ICMPv6 | echo-request | accept | Servers ping the gateway for diagnostics. |
| 999 | any  | any  | drop   | |

---

### V6ONLY → WAN
The NPTv6 safety drop at rule 5 lives in the 1–9 defensive-drop range (see
*WAN ingress anti-spoofing*).

| # | Proto | Match | Action | Rationale |
|---|-------|-------|--------|-----------|
| 5  | IPv6  | source fc00::/7 | drop + log | NPTv6 safety: if the nat66 rule fails to translate, raw ULA packets must not leak to WAN. |
| 20 | any   | any   | accept | IPv6-only hosts must reach the internet. NPTv6 (N2) rewrites the source ULA prefix to the routable outer prefix. This is the entire purpose of the segment. |

> **Encoding caveat (rule 5):** the zone/forward ruleset is evaluated *before*
> NPTv6 (which runs in POSTROUTING), so on this path the firewall always sees the
> pre-translation ULA source `fd07:1:1:1::/64` (itself inside `fc00::/7`).
> Dropping `fc00::/7` here would block all legitimate egress, so **rule 5 is not
> encoded on V6ONLY → WAN** (06). The leak it guards against can't occur because
> the NPTv6 source rule covers the whole `fd07:1:1:1::/64`; the inbound direction
> is still protected by the `fc00::/7` bogon drop in WAN → LOCAL.

### V6ONLY → LOCAL
| # | Proto | Port | Action | Rationale |
|---|-------|------|--------|-----------|
| 20 | UDP+TCP | 53 | accept | DNS. IPv6-only hosts resolve names via VyOS. |
| 21 | UDP   | 123  | accept | NTP relay. |
| 22 | ICMPv6 | Group A + Group B types | accept | NDP, path-MTU, error signaling. Uses the named ICMPv6 groups defined below — Group A (types 1/2/3/4/135/136) plus Group B (types 133/134), since V6ONLY hosts legitimately send RS and receive RA on this LAN-facing interface. |
| 999 | any  | any  | drop   | |

---

### VPN → INTERNAL
Traffic from WireGuard tunnel clients (tunnel subnet 10.7.99.0/24 + fd07:99::/64) to internal workstations and servers.
VPN users are remote administrators, not anonymous clients. Access is scoped to defined management
protocols — least-privilege applies here as everywhere else in this policy.

| # | Proto | Port | Action | Rationale |
|---|-------|------|--------|-----------|
| 20 | TCP   | 22   | accept | SSH to internal Linux hosts for remote administration. |
| 21 | TCP   | 3389 | accept | RDP to ws-02 (Windows). Remote workers need GUI access to the Windows client (S9.2). |
| 22 | ICMPv4+ICMPv6 | echo-request | accept | Ping for tunnel connectivity diagnostics. |
| 999 | any  | any  | drop   | All other INTERNAL access is denied. VPN is a management path, not a blanket trust grant. |

### VPN → DMZ
VPN clients resolve DNS and sync NTP via the router (covered in VPN → LOCAL); Kerberos/DNS
to the DMZ directly is not needed. This block covers service access and server administration only.

| # | Proto | Port | Action | Rationale |
|---|-------|------|--------|-----------|
| 20 | TCP   | 443  | accept | REST API and Grafana dashboard access over HTTPS. |
| 21 | TCP   | 22   | accept | SSH to DMZ servers for administration. |
| 22 | TCP   | 636  | accept | LDAPS for applications that require direct directory binds from the admin's session. |
| 999 | any  | any  | drop   | |

### VPN → LOCAL
| # | Proto | Port | Action | Rationale |
|---|-------|------|--------|-----------|
| 20 | TCP   | 22   | accept | SSH to the router for remote management — the primary use case for the VPN. |
| 30 | UDP+TCP | 53 | accept | DNS. VPN clients receive VyOS as their pushed DNS server. |
| 31 | UDP   | 123  | accept | NTP. |
| 999 | any  | any  | drop   | |

---

### LOCAL → any
| # | Action | Rationale |
|---|--------|-----------|
| 20 | accept | Router-initiated traffic (NTP sync to upstream servers, DNS queries, WireGuard handshakes, SNMP traps if configured) must always be allowed. |

---

## Ports not exposed (intentional omissions)

| Port / Service | Why excluded |
|---|---|
| TCP/22 from WAN | SSH to the router is VPN-only. Prevents brute-force and exposure of management plane. |
| TCP/9090 (Prometheus) | Internal scraping only. No external access to metrics. |
| TCP/5432 (PostgreSQL) | Database replication is intra-DMZ. No external or internal-to-DMZ access needed. |
| TCP/2379-2380 (etcd) | Intra-DMZ cluster traffic only. |
| TCP/389 (plain LDAP) | Dropped explicitly. All directory traffic must use LDAPS (636). |
| TCP/80 (HTTP) inbound | REST API is HTTPS-only. nginx redirects 80→443 on the server; no need to expose port 80 on the firewall. |
| UDP/161 (SNMP) except from 192.168.7.20 | SNMP community strings are not encrypted; restricting to the known monitoring source prevents information leakage. |
| TCP/443 (IPA Web UI) from WAN | FreeIPA admin interface is internal-only. No external access. |

---

## ICMPv6 special handling

ICMPv6 carries control traffic essential to IPv6 operation. Types are split into two groups
by trust scope: those safe to allow from any direction (including WAN), and those that must
be confined to LAN-facing interfaces.

### Group A — allow on all interfaces, including WAN ingress

| Type | Name | Reason |
|------|------|--------|
| 1    | Destination Unreachable | Error signaling. Applications need this to detect unreachable hosts and ports. |
| 2    | Packet Too Big | Path-MTU discovery (RFC 4890 §4.3.1). Without it, flows over tunnels silently black-hole at MTU mismatches. |
| 3    | Time Exceeded | Hop-limit errors; required for traceroute and loop detection. |
| 4    | Parameter Problem | Malformed-header notification; needed for correct protocol operation. |
| 135  | Neighbor Solicitation | NDP address resolution — IPv6 equivalent of ARP. Must be accepted from any host on the link. |
| 136  | Neighbor Advertisement | NDP reply — same rationale as NS. |

### Group B — LAN-facing interfaces only (INTERNAL, DMZ, V6ONLY, LOCAL); drop on WAN ingress

| Type | Name | Reason |
|------|------|--------|
| 133  | Router Solicitation | Hosts send RS to solicit an RA from their local router. Safe inbound on LAN; no need to accept from WAN. |
| 134  | Router Advertisement | **Must not be accepted inbound from WAN.** A crafted RA from WAN could rewrite the default IPv6 route on every LAN host (rogue-RA attack, RFC 6104). VyOS is the only authorised RA sender on these segments; inbound RAs from an external source are illegitimate. |

### RA-Guard note

This firewall rule prevents WAN-sourced rogue RAs from reaching LAN hosts. It does not
prevent a rogue RA from a compromised host *within* a segment. If the ESXi port-group
security policy supports it, enable RA-Guard (VLAN ACLs) on the access vSwitches.
Document the limitation if the hypervisor does not support it.

VyOS has no "ICMP-type group" construct, so these aren't literal `firewall group`
objects. On LAN-facing ingress (INTERNAL/DMZ/V6ONLY → LOCAL) the encoding (06)
simply accepts `protocol ipv6-icmp` wholesale — NDP, RS/RA, echo and errors are
all legitimate there. On WAN ingress the Group A types are accepted as **explicit
per-type rules** (`icmpv6 type echo-request|neighbor-solicitation|…`), the error
types (1–4) ride in as `related` under the global state-policy, and Group B
(133/134) is simply *not* listed → dropped by default (rogue-RA protection).

---

## WAN ingress anti-spoofing and bogon filtering

Packets arriving on eth0 with source addresses from private, loopback, or our own internal
ranges cannot be legitimate internet traffic — they are either spoofed or a routing mistake.
Drop these before any zone-pair accept or state rules fire. Per the canonical layout above,
these drops occupy **rules 1–9** in the WAN → LOCAL and WAN → DMZ rulesets, ahead of the
stateful baseline at 10–11. The WAN → INTERNAL and WAN → V6ONLY rulesets are already
default-drop, so the pre-filter there is redundant but harmless to add for symmetry.

The V6ONLY → WAN NPTv6 safety drop (rule 5) sits in the same 1–9 range — same conceptual
category: drop the obviously-malformed source before evaluating anything else.

### IPv4 — drop if WAN source is in:

| Prefix | Category |
|--------|----------|
| 10.0.0.0/8 | RFC 1918 (includes our 10.7.0.0/24) |
| 172.16.0.0/12 | RFC 1918 |
| 192.168.0.0/16 | RFC 1918 (includes our 192.168.7.0/24) |
| 127.0.0.0/8 | Loopback |
| 169.254.0.0/16 | Link-local / APIPA |
| 0.0.0.0/8 | "This" network (RFC 1122) |
| 240.0.0.0/4 | Reserved |
| 224.0.0.0/4 | Multicast (invalid as unicast source) |

### IPv6 — drop if WAN source is in:

| Prefix | Category |
|--------|----------|
| fc00::/7 | ULA (includes our own fd07:1:1:1::/64) |
| ::1/128 | Loopback |
| fe80::/10 | Link-local |
| ::/128 | Unspecified |
| 2001:1470:fffd:99::/64 | Our own DMZ prefix — spoofed source |
| 2001:1470:fffd:9a::/64 | Our own internal prefix — spoofed source |
| 2001:1470:fffd:9b::/64 | NPTv6 outer translation prefix — return traffic to V6ONLY hosts uses this as a *destination*, never a source; a WAN packet sourced from it is spoofed |

The WAN link prefix `2001:1470:fffd:98::/64` is **not** filtered as a bogon source —
the upstream gateway and any neighbour on that link legitimately source from there.

---

## Logging policy

Logging is selective — not every drop is worth storing. The rationale: high-volume
silent paths (e.g. INTERNAL → WAN being unreachable because a connection was reset) would
flood syslog with noise. Security-relevant paths are logged explicitly.

| Zone pair / rule | Logged? | Reason |
|---|---|---|
| WAN → LOCAL rule 999 (catch-all drop) | yes | Unauthorised inbound attempts to the router are forensically relevant. |
| WAN → DMZ rule 999 (catch-all drop) | yes | Unexpected inbound attempts to DMZ may signal scanning or misconfigured DNAT. |
| WAN → INTERNAL (entire default-drop) | yes | Any packet reaching this pair is already suspicious; log everything. |
| DMZ → INTERNAL rule 999 | yes | Lateral movement attempt — high value for incident detection. |
| Anti-spoofing / bogon rules (all, rules 1–9 on WAN inbound) | yes | Spoofed sources on WAN are always noteworthy. |
| V6ONLY → WAN rule 5 (NPTv6 safety) | yes | Fires only on misconfiguration; should be immediately visible. |
| All other default-drops | no | Silent. Noise from ephemeral connection resets and normal asymmetric traffic would outweigh signal. |

---

## Notes for runbook encoding (N6)

Encoded zone-based on VyOS 1.4.4 in `vyos/06-firewall-setup.md`. Syntax confirmed
on the box:

- Zones: `set firewall zone <zone> interface <eth>` (note: `interface`, not
  `member interface` — that's the 1.5 form), `set firewall zone <zone>
  default-action drop`, and `set firewall zone LOCAL local-zone` for the router.
- Each zone-pair is one named chain attached with
  `set firewall zone <TO> from <FROM> firewall name <ruleset>` (IPv4) /
  `firewall ipv6-name <ruleset>` (IPv6). Unlisted from-zones fall through to the
  destination zone's `default-action drop`, which gives the "intentionally
  unlisted pairs" for free.
- Rule tables: `set firewall ipv4 name <name> rule …` / `ipv6 name …`.
- Naming convention: `<FROM>-<TO>` e.g. `WAN-DMZ`, `WAN-DMZ6` for IPv6.
- Stateful baseline is a single global `firewall global-options state-policy`
  (established/related accept, invalid drop) — not rules 10/11 per chain.
- WAN-ingress bogons are a `firewall group network-group` / `ipv6-network-group`
  referenced from the WAN → LOCAL and WAN → DMZ chains (rule 1).
- The SNMP source restriction (DMZ → LOCAL rule 24) uses a `firewall group
  address-group` / `ipv6-address-group` matched as `source group …`.
- `LOCAL → any` is a permissive `LOCAL-OUT` chain attached `from LOCAL` on every
  zone, so router-initiated NTP/DNS/update flows (new connections, not just
  replies) are allowed.
- WireGuard `wg0` will carry dual-stack tunnel addresses (10.7.99.0/24 +
  fd07:99::/64); the `VPN` zone and its rules are added in the N7 runbook once the
  `wg0` interface exists, at which point the temporary WAN → LOCAL SSH accept is
  removed.
