# 06 — Firewall (N6)

Zone-based encoding of `network/firewall-policy.md` on VyOS 1.4.4. Dual-stack.
Cite: **N6.1**, **N6.2**.

**Model: zone-based** (`firewall zone`) — maps 1:1 to the policy's zone-pair
matrix. Each pair `<FROM> → <TO>` is a named chain (`firewall ipv4|ipv6 name
<FROM>-<TO>`) attached with `zone <TO> from <FROM>`. The router itself is the
`LOCAL` local-zone.

One idea borrowed from the 1.4 hook model because it's the cleaner 1.4.4 idiom
either way: the stateful baseline (established/related/invalid) is a single global
`state-policy`, not rules 10/11 repeated in every chain.

## ⚠️ Read before you commit — remote-lockout risk

This box is managed over SSH **through eth0 (WAN)**. The policy makes management
VPN-only (N7), but WireGuard isn't deployed yet — a clean default-drop would cut
your own session. Two safeguards:

1. **Temporary `WAN → LOCAL` SSH accept (rule 40).** Marked `TEMP`; delete it the
   moment N7 is live. Harden now by source-restricting to your admin IP (commented).
2. **`commit-confirm 10`** — applies but auto-reverts in 10 min unless you type
   `confirm`. **Do not `save` until after `confirm`.**

Zone leaf names confirmed on this box: `zone <z> interface <eth>`,
`zone <z> from <src> firewall name|ipv6-name <ruleset>`, `zone LOCAL local-zone`,
`zone <z> default-action`. If another `set` line is rejected, tab-complete it
(`icmpv6 type` and `limit rate` formatting are the next most likely to vary).

---

## 1. Apply (single atomic configure session)

```sh
configure
```

### 1a. Stateful baseline (one global policy)
```sh
set firewall global-options state-policy established action 'accept'
set firewall global-options state-policy related action 'accept'
set firewall global-options state-policy invalid action 'drop'
```

### 1b. Groups — WAN bogons + SNMP source allow-list
```sh
set firewall group network-group BOGONS-V4 network '10.0.0.0/8'
set firewall group network-group BOGONS-V4 network '172.16.0.0/12'
set firewall group network-group BOGONS-V4 network '192.168.0.0/16'
set firewall group network-group BOGONS-V4 network '127.0.0.0/8'
set firewall group network-group BOGONS-V4 network '169.254.0.0/16'
set firewall group network-group BOGONS-V4 network '0.0.0.0/8'
set firewall group network-group BOGONS-V4 network '240.0.0.0/4'
set firewall group network-group BOGONS-V4 network '224.0.0.0/4'

set firewall group ipv6-network-group BOGONS-V6 network 'fc00::/7'
set firewall group ipv6-network-group BOGONS-V6 network '::1/128'
set firewall group ipv6-network-group BOGONS-V6 network 'fe80::/10'
set firewall group ipv6-network-group BOGONS-V6 network '::/128'
set firewall group ipv6-network-group BOGONS-V6 network '2001:1470:fffd:99::/64'
set firewall group ipv6-network-group BOGONS-V6 network '2001:1470:fffd:9a::/64'
set firewall group ipv6-network-group BOGONS-V6 network '2001:1470:fffd:9b::/64'

set firewall group address-group SNMP-MON-V4 address '192.168.7.20'
set firewall group ipv6-address-group SNMP-MON-V6 address '2001:1470:fffd:99::20'
```
> `BOGONS-V6` covers our ULA `fd07:1:1:1::/64` (inside `fc00::/7`) and all three
> assigned /64s as spoofed sources. The WAN link prefix `…98::/64` is *not*
> listed — the upstream gateway legitimately sources from it.

### 1c. LOCAL → any  (router-originated traffic always passes)
```sh
set firewall ipv4 name LOCAL-OUT default-action 'accept'
set firewall ipv6 name LOCAL-OUT6 default-action 'accept'
```

### 1d. WAN → LOCAL
```sh
set firewall ipv4 name WAN-LOCAL default-action 'drop'
set firewall ipv4 name WAN-LOCAL enable-default-log
set firewall ipv4 name WAN-LOCAL rule 1 description 'anti-spoof bogon'
set firewall ipv4 name WAN-LOCAL rule 1 source group network-group 'BOGONS-V4'
set firewall ipv4 name WAN-LOCAL rule 1 action 'drop'
set firewall ipv4 name WAN-LOCAL rule 1 log
set firewall ipv4 name WAN-LOCAL rule 20 description 'icmp echo (rate-limited)'
set firewall ipv4 name WAN-LOCAL rule 20 protocol 'icmp'
set firewall ipv4 name WAN-LOCAL rule 20 icmp type-name 'echo-request'
set firewall ipv4 name WAN-LOCAL rule 20 limit rate '10/second'
set firewall ipv4 name WAN-LOCAL rule 20 action 'accept'
set firewall ipv4 name WAN-LOCAL rule 30 description 'WireGuard endpoint (N7)'
set firewall ipv4 name WAN-LOCAL rule 30 protocol 'udp'
set firewall ipv4 name WAN-LOCAL rule 30 destination port '51820'
set firewall ipv4 name WAN-LOCAL rule 30 action 'accept'
set firewall ipv4 name WAN-LOCAL rule 40 description 'TEMP admin SSH - REMOVE after N7'
set firewall ipv4 name WAN-LOCAL rule 40 protocol 'tcp'
set firewall ipv4 name WAN-LOCAL rule 40 destination port '22'
set firewall ipv4 name WAN-LOCAL rule 40 action 'accept'
# HARDEN: restrict the temp rule to your admin host, e.g.
# set firewall ipv4 name WAN-LOCAL rule 40 source address '<your.admin.ip>/32'

set firewall ipv6 name WAN-LOCAL6 default-action 'drop'
set firewall ipv6 name WAN-LOCAL6 enable-default-log
set firewall ipv6 name WAN-LOCAL6 rule 1 description 'anti-spoof bogon'
set firewall ipv6 name WAN-LOCAL6 rule 1 source group ipv6-network-group 'BOGONS-V6'
set firewall ipv6 name WAN-LOCAL6 rule 1 action 'drop'
set firewall ipv6 name WAN-LOCAL6 rule 1 log
set firewall ipv6 name WAN-LOCAL6 rule 20 description 'icmpv6 echo (rate-limited)'
set firewall ipv6 name WAN-LOCAL6 rule 20 protocol 'ipv6-icmp'
set firewall ipv6 name WAN-LOCAL6 rule 20 icmpv6 type 'echo-request'
set firewall ipv6 name WAN-LOCAL6 rule 20 limit rate '10/second'
set firewall ipv6 name WAN-LOCAL6 rule 20 action 'accept'
set firewall ipv6 name WAN-LOCAL6 rule 21 description 'NDP neighbor-solicitation'
set firewall ipv6 name WAN-LOCAL6 rule 21 protocol 'ipv6-icmp'
set firewall ipv6 name WAN-LOCAL6 rule 21 icmpv6 type 'neighbor-solicitation'
set firewall ipv6 name WAN-LOCAL6 rule 21 action 'accept'
set firewall ipv6 name WAN-LOCAL6 rule 22 description 'NDP neighbor-advertisement'
set firewall ipv6 name WAN-LOCAL6 rule 22 protocol 'ipv6-icmp'
set firewall ipv6 name WAN-LOCAL6 rule 22 icmpv6 type 'neighbor-advertisement'
set firewall ipv6 name WAN-LOCAL6 rule 22 action 'accept'
set firewall ipv6 name WAN-LOCAL6 rule 30 protocol 'udp'
set firewall ipv6 name WAN-LOCAL6 rule 30 destination port '51820'
set firewall ipv6 name WAN-LOCAL6 rule 30 action 'accept'
set firewall ipv6 name WAN-LOCAL6 rule 40 description 'TEMP admin SSH - REMOVE after N7'
set firewall ipv6 name WAN-LOCAL6 rule 40 protocol 'tcp'
set firewall ipv6 name WAN-LOCAL6 rule 40 destination port '22'
set firewall ipv6 name WAN-LOCAL6 rule 40 action 'accept'
```
> ICMPv6 error types (1–4) arrive as `related` and are accepted by the global
> state-policy — no explicit rule needed. Router Solicitation/Advertisement
> (133/134) are deliberately not accepted from WAN → rogue-RA protection.

### 1e. WAN → DMZ  (HTTPS only; external reach needs I1 DNAT)
```sh
set firewall ipv4 name WAN-DMZ default-action 'drop'
set firewall ipv4 name WAN-DMZ enable-default-log
set firewall ipv4 name WAN-DMZ rule 1 source group network-group 'BOGONS-V4'
set firewall ipv4 name WAN-DMZ rule 1 action 'drop'
set firewall ipv4 name WAN-DMZ rule 1 log
set firewall ipv4 name WAN-DMZ rule 20 description 'HTTPS / REST API'
set firewall ipv4 name WAN-DMZ rule 20 protocol 'tcp'
set firewall ipv4 name WAN-DMZ rule 20 destination port '443'
set firewall ipv4 name WAN-DMZ rule 20 action 'accept'
set firewall ipv4 name WAN-DMZ rule 21 description 'HTTP/3 QUIC (opt S3.10)'
set firewall ipv4 name WAN-DMZ rule 21 protocol 'udp'
set firewall ipv4 name WAN-DMZ rule 21 destination port '443'
set firewall ipv4 name WAN-DMZ rule 21 action 'accept'

set firewall ipv6 name WAN-DMZ6 default-action 'drop'
set firewall ipv6 name WAN-DMZ6 enable-default-log
set firewall ipv6 name WAN-DMZ6 rule 1 source group ipv6-network-group 'BOGONS-V6'
set firewall ipv6 name WAN-DMZ6 rule 1 action 'drop'
set firewall ipv6 name WAN-DMZ6 rule 1 log
set firewall ipv6 name WAN-DMZ6 rule 20 protocol 'tcp'
set firewall ipv6 name WAN-DMZ6 rule 20 destination port '443'
set firewall ipv6 name WAN-DMZ6 rule 20 action 'accept'
set firewall ipv6 name WAN-DMZ6 rule 21 protocol 'udp'
set firewall ipv6 name WAN-DMZ6 rule 21 destination port '443'
set firewall ipv6 name WAN-DMZ6 rule 21 action 'accept'
```

### 1f. WAN → INTERNAL  (drop everything, logged)
```sh
set firewall ipv4 name WAN-INTERNAL default-action 'drop'
set firewall ipv4 name WAN-INTERNAL enable-default-log
set firewall ipv6 name WAN-INTERNAL6 default-action 'drop'
set firewall ipv6 name WAN-INTERNAL6 enable-default-log
```

### 1g. INTERNAL → WAN  (unrestricted outbound; NAT in 04)
```sh
set firewall ipv4 name INTERNAL-WAN default-action 'accept'
set firewall ipv6 name INTERNAL-WAN6 default-action 'accept'
```

### 1h. INTERNAL → DMZ
```sh
set firewall ipv4 name INTERNAL-DMZ default-action 'drop'
set firewall ipv4 name INTERNAL-DMZ rule 20 description 'HTTPS (REST API, Grafana)'
set firewall ipv4 name INTERNAL-DMZ rule 20 protocol 'tcp'
set firewall ipv4 name INTERNAL-DMZ rule 20 destination port '443'
set firewall ipv4 name INTERNAL-DMZ rule 20 action 'accept'
set firewall ipv4 name INTERNAL-DMZ rule 21 description 'SSH admin to DMZ'
set firewall ipv4 name INTERNAL-DMZ rule 21 protocol 'tcp'
set firewall ipv4 name INTERNAL-DMZ rule 21 destination port '22'
set firewall ipv4 name INTERNAL-DMZ rule 21 action 'accept'
set firewall ipv4 name INTERNAL-DMZ rule 30 description 'DNS to FreeIPA'
set firewall ipv4 name INTERNAL-DMZ rule 30 protocol 'tcp_udp'
set firewall ipv4 name INTERNAL-DMZ rule 30 destination port '53'
set firewall ipv4 name INTERNAL-DMZ rule 30 action 'accept'
set firewall ipv4 name INTERNAL-DMZ rule 31 description 'LDAPS'
set firewall ipv4 name INTERNAL-DMZ rule 31 protocol 'tcp'
set firewall ipv4 name INTERNAL-DMZ rule 31 destination port '636'
set firewall ipv4 name INTERNAL-DMZ rule 31 action 'accept'
set firewall ipv4 name INTERNAL-DMZ rule 32 description 'Kerberos (opt S9.1)'
set firewall ipv4 name INTERNAL-DMZ rule 32 protocol 'tcp_udp'
set firewall ipv4 name INTERNAL-DMZ rule 32 destination port '88'
set firewall ipv4 name INTERNAL-DMZ rule 32 action 'accept'
set firewall ipv4 name INTERNAL-DMZ rule 33 description 'kpasswd (opt S9.1)'
set firewall ipv4 name INTERNAL-DMZ rule 33 protocol 'tcp_udp'
set firewall ipv4 name INTERNAL-DMZ rule 33 destination port '464'
set firewall ipv4 name INTERNAL-DMZ rule 33 action 'accept'
set firewall ipv4 name INTERNAL-DMZ rule 34 description 'plain LDAP forbidden'
set firewall ipv4 name INTERNAL-DMZ rule 34 protocol 'tcp'
set firewall ipv4 name INTERNAL-DMZ rule 34 destination port '389'
set firewall ipv4 name INTERNAL-DMZ rule 34 action 'drop'

set firewall ipv6 name INTERNAL-DMZ6 default-action 'drop'
set firewall ipv6 name INTERNAL-DMZ6 rule 20 protocol 'tcp'
set firewall ipv6 name INTERNAL-DMZ6 rule 20 destination port '443'
set firewall ipv6 name INTERNAL-DMZ6 rule 20 action 'accept'
set firewall ipv6 name INTERNAL-DMZ6 rule 21 protocol 'tcp'
set firewall ipv6 name INTERNAL-DMZ6 rule 21 destination port '22'
set firewall ipv6 name INTERNAL-DMZ6 rule 21 action 'accept'
set firewall ipv6 name INTERNAL-DMZ6 rule 30 protocol 'tcp_udp'
set firewall ipv6 name INTERNAL-DMZ6 rule 30 destination port '53'
set firewall ipv6 name INTERNAL-DMZ6 rule 30 action 'accept'
set firewall ipv6 name INTERNAL-DMZ6 rule 31 protocol 'tcp'
set firewall ipv6 name INTERNAL-DMZ6 rule 31 destination port '636'
set firewall ipv6 name INTERNAL-DMZ6 rule 31 action 'accept'
set firewall ipv6 name INTERNAL-DMZ6 rule 32 protocol 'tcp_udp'
set firewall ipv6 name INTERNAL-DMZ6 rule 32 destination port '88'
set firewall ipv6 name INTERNAL-DMZ6 rule 32 action 'accept'
set firewall ipv6 name INTERNAL-DMZ6 rule 33 protocol 'tcp_udp'
set firewall ipv6 name INTERNAL-DMZ6 rule 33 destination port '464'
set firewall ipv6 name INTERNAL-DMZ6 rule 33 action 'accept'
set firewall ipv6 name INTERNAL-DMZ6 rule 34 protocol 'tcp'
set firewall ipv6 name INTERNAL-DMZ6 rule 34 destination port '389'
set firewall ipv6 name INTERNAL-DMZ6 rule 34 action 'drop'
```

### 1i. INTERNAL → LOCAL
```sh
set firewall ipv4 name INTERNAL-LOCAL default-action 'drop'
set firewall ipv4 name INTERNAL-LOCAL rule 20 description 'SSH mgmt of router'
set firewall ipv4 name INTERNAL-LOCAL rule 20 protocol 'tcp'
set firewall ipv4 name INTERNAL-LOCAL rule 20 destination port '22'
set firewall ipv4 name INTERNAL-LOCAL rule 20 action 'accept'
set firewall ipv4 name INTERNAL-LOCAL rule 30 description 'DNS forwarder'
set firewall ipv4 name INTERNAL-LOCAL rule 30 protocol 'tcp_udp'
set firewall ipv4 name INTERNAL-LOCAL rule 30 destination port '53'
set firewall ipv4 name INTERNAL-LOCAL rule 30 action 'accept'
set firewall ipv4 name INTERNAL-LOCAL rule 31 description 'NTP relay'
set firewall ipv4 name INTERNAL-LOCAL rule 31 protocol 'udp'
set firewall ipv4 name INTERNAL-LOCAL rule 31 destination port '123'
set firewall ipv4 name INTERNAL-LOCAL rule 31 action 'accept'
set firewall ipv4 name INTERNAL-LOCAL rule 32 description 'DHCPv4'
set firewall ipv4 name INTERNAL-LOCAL rule 32 protocol 'udp'
set firewall ipv4 name INTERNAL-LOCAL rule 32 destination port '67'
set firewall ipv4 name INTERNAL-LOCAL rule 32 action 'accept'
set firewall ipv4 name INTERNAL-LOCAL rule 34 description 'ping gateway'
set firewall ipv4 name INTERNAL-LOCAL rule 34 protocol 'icmp'
set firewall ipv4 name INTERNAL-LOCAL rule 34 icmp type-name 'echo-request'
set firewall ipv4 name INTERNAL-LOCAL rule 34 action 'accept'

set firewall ipv6 name INTERNAL-LOCAL6 default-action 'drop'
set firewall ipv6 name INTERNAL-LOCAL6 rule 20 protocol 'tcp'
set firewall ipv6 name INTERNAL-LOCAL6 rule 20 destination port '22'
set firewall ipv6 name INTERNAL-LOCAL6 rule 20 action 'accept'
set firewall ipv6 name INTERNAL-LOCAL6 rule 30 protocol 'tcp_udp'
set firewall ipv6 name INTERNAL-LOCAL6 rule 30 destination port '53'
set firewall ipv6 name INTERNAL-LOCAL6 rule 30 action 'accept'
set firewall ipv6 name INTERNAL-LOCAL6 rule 31 protocol 'udp'
set firewall ipv6 name INTERNAL-LOCAL6 rule 31 destination port '123'
set firewall ipv6 name INTERNAL-LOCAL6 rule 31 action 'accept'
set firewall ipv6 name INTERNAL-LOCAL6 rule 33 description 'DHCPv6'
set firewall ipv6 name INTERNAL-LOCAL6 rule 33 protocol 'udp'
set firewall ipv6 name INTERNAL-LOCAL6 rule 33 destination port '547'
set firewall ipv6 name INTERNAL-LOCAL6 rule 33 action 'accept'
set firewall ipv6 name INTERNAL-LOCAL6 rule 34 description 'NDP/RS/RA + icmp'
set firewall ipv6 name INTERNAL-LOCAL6 rule 34 protocol 'ipv6-icmp'
set firewall ipv6 name INTERNAL-LOCAL6 rule 34 action 'accept'
```
> On LAN-facing ingress all `ipv6-icmp` is accepted (NDP + RS/RA are legitimate
> here, unlike on WAN).

### 1j. DMZ → WAN  (updates/NTP/diag only)
```sh
set firewall ipv4 name DMZ-WAN default-action 'drop'
set firewall ipv4 name DMZ-WAN rule 20 description 'HTTP (apt)'
set firewall ipv4 name DMZ-WAN rule 20 protocol 'tcp'
set firewall ipv4 name DMZ-WAN rule 20 destination port '80'
set firewall ipv4 name DMZ-WAN rule 20 action 'accept'
set firewall ipv4 name DMZ-WAN rule 21 description 'HTTPS (apt/pip/CRL/OCSP)'
set firewall ipv4 name DMZ-WAN rule 21 protocol 'tcp'
set firewall ipv4 name DMZ-WAN rule 21 destination port '443'
set firewall ipv4 name DMZ-WAN rule 21 action 'accept'
set firewall ipv4 name DMZ-WAN rule 22 description 'NTP upstream fallback'
set firewall ipv4 name DMZ-WAN rule 22 protocol 'udp'
set firewall ipv4 name DMZ-WAN rule 22 destination port '123'
set firewall ipv4 name DMZ-WAN rule 22 action 'accept'
set firewall ipv4 name DMZ-WAN rule 23 description 'ping diag'
set firewall ipv4 name DMZ-WAN rule 23 protocol 'icmp'
set firewall ipv4 name DMZ-WAN rule 23 icmp type-name 'echo-request'
set firewall ipv4 name DMZ-WAN rule 23 action 'accept'

set firewall ipv6 name DMZ-WAN6 default-action 'drop'
set firewall ipv6 name DMZ-WAN6 rule 20 protocol 'tcp'
set firewall ipv6 name DMZ-WAN6 rule 20 destination port '80'
set firewall ipv6 name DMZ-WAN6 rule 20 action 'accept'
set firewall ipv6 name DMZ-WAN6 rule 21 protocol 'tcp'
set firewall ipv6 name DMZ-WAN6 rule 21 destination port '443'
set firewall ipv6 name DMZ-WAN6 rule 21 action 'accept'
set firewall ipv6 name DMZ-WAN6 rule 22 protocol 'udp'
set firewall ipv6 name DMZ-WAN6 rule 22 destination port '123'
set firewall ipv6 name DMZ-WAN6 rule 22 action 'accept'
set firewall ipv6 name DMZ-WAN6 rule 23 description 'ping diag'
set firewall ipv6 name DMZ-WAN6 rule 23 protocol 'ipv6-icmp'
set firewall ipv6 name DMZ-WAN6 rule 23 icmpv6 type 'echo-request'
set firewall ipv6 name DMZ-WAN6 rule 23 action 'accept'
```

### 1k. DMZ → INTERNAL  (no lateral movement; logged)
```sh
set firewall ipv4 name DMZ-INTERNAL default-action 'drop'
set firewall ipv4 name DMZ-INTERNAL enable-default-log
set firewall ipv6 name DMZ-INTERNAL6 default-action 'drop'
set firewall ipv6 name DMZ-INTERNAL6 enable-default-log
```

### 1l. DMZ → LOCAL
```sh
set firewall ipv4 name DMZ-LOCAL default-action 'drop'
set firewall ipv4 name DMZ-LOCAL rule 20 description 'DNS forwarder'
set firewall ipv4 name DMZ-LOCAL rule 20 protocol 'tcp_udp'
set firewall ipv4 name DMZ-LOCAL rule 20 destination port '53'
set firewall ipv4 name DMZ-LOCAL rule 20 action 'accept'
set firewall ipv4 name DMZ-LOCAL rule 21 description 'NTP relay'
set firewall ipv4 name DMZ-LOCAL rule 21 protocol 'udp'
set firewall ipv4 name DMZ-LOCAL rule 21 destination port '123'
set firewall ipv4 name DMZ-LOCAL rule 21 action 'accept'
set firewall ipv4 name DMZ-LOCAL rule 22 description 'DHCPv4 renew'
set firewall ipv4 name DMZ-LOCAL rule 22 protocol 'udp'
set firewall ipv4 name DMZ-LOCAL rule 22 destination port '67'
set firewall ipv4 name DMZ-LOCAL rule 22 action 'accept'
set firewall ipv4 name DMZ-LOCAL rule 24 description 'SNMP from mon only'
set firewall ipv4 name DMZ-LOCAL rule 24 protocol 'udp'
set firewall ipv4 name DMZ-LOCAL rule 24 destination port '161'
set firewall ipv4 name DMZ-LOCAL rule 24 source group address-group 'SNMP-MON-V4'
set firewall ipv4 name DMZ-LOCAL rule 24 action 'accept'
set firewall ipv4 name DMZ-LOCAL rule 25 description 'SSH mgmt of router'
set firewall ipv4 name DMZ-LOCAL rule 25 protocol 'tcp'
set firewall ipv4 name DMZ-LOCAL rule 25 destination port '22'
set firewall ipv4 name DMZ-LOCAL rule 25 action 'accept'
set firewall ipv4 name DMZ-LOCAL rule 26 description 'ping gateway'
set firewall ipv4 name DMZ-LOCAL rule 26 protocol 'icmp'
set firewall ipv4 name DMZ-LOCAL rule 26 icmp type-name 'echo-request'
set firewall ipv4 name DMZ-LOCAL rule 26 action 'accept'

set firewall ipv6 name DMZ-LOCAL6 default-action 'drop'
set firewall ipv6 name DMZ-LOCAL6 rule 20 protocol 'tcp_udp'
set firewall ipv6 name DMZ-LOCAL6 rule 20 destination port '53'
set firewall ipv6 name DMZ-LOCAL6 rule 20 action 'accept'
set firewall ipv6 name DMZ-LOCAL6 rule 21 protocol 'udp'
set firewall ipv6 name DMZ-LOCAL6 rule 21 destination port '123'
set firewall ipv6 name DMZ-LOCAL6 rule 21 action 'accept'
set firewall ipv6 name DMZ-LOCAL6 rule 23 description 'DHCPv6'
set firewall ipv6 name DMZ-LOCAL6 rule 23 protocol 'udp'
set firewall ipv6 name DMZ-LOCAL6 rule 23 destination port '547'
set firewall ipv6 name DMZ-LOCAL6 rule 23 action 'accept'
set firewall ipv6 name DMZ-LOCAL6 rule 24 description 'SNMP from mon only'
set firewall ipv6 name DMZ-LOCAL6 rule 24 protocol 'udp'
set firewall ipv6 name DMZ-LOCAL6 rule 24 destination port '161'
set firewall ipv6 name DMZ-LOCAL6 rule 24 source group ipv6-address-group 'SNMP-MON-V6'
set firewall ipv6 name DMZ-LOCAL6 rule 24 action 'accept'
set firewall ipv6 name DMZ-LOCAL6 rule 25 protocol 'tcp'
set firewall ipv6 name DMZ-LOCAL6 rule 25 destination port '22'
set firewall ipv6 name DMZ-LOCAL6 rule 25 action 'accept'
set firewall ipv6 name DMZ-LOCAL6 rule 26 description 'NDP + icmp'
set firewall ipv6 name DMZ-LOCAL6 rule 26 protocol 'ipv6-icmp'
set firewall ipv6 name DMZ-LOCAL6 rule 26 action 'accept'
```

### 1m. V6ONLY → WAN  (IPv6-only egress; NPTv6 in 02/04)
```sh
set firewall ipv6 name V6ONLY-WAN6 default-action 'accept'
```
> The policy's rule-5 "drop `fc00::/7` source" is **not** encoded here: the zone
> ruleset runs *before* NPTv6 (POSTROUTING), so it always sees the inner ULA
> source `fd07:1:1:1::/64` — dropping it would block all legitimate egress.
> NPTv6 (02/04) translates the whole `fd07:1:1:1::/64`, so an untranslated leak
> can't occur for that prefix; inbound `fc00::/7` is already dropped by WAN-LOCAL6.

### 1n. V6ONLY → LOCAL
```sh
set firewall ipv6 name V6ONLY-LOCAL6 default-action 'drop'
set firewall ipv6 name V6ONLY-LOCAL6 rule 20 description 'DNS'
set firewall ipv6 name V6ONLY-LOCAL6 rule 20 protocol 'tcp_udp'
set firewall ipv6 name V6ONLY-LOCAL6 rule 20 destination port '53'
set firewall ipv6 name V6ONLY-LOCAL6 rule 20 action 'accept'
set firewall ipv6 name V6ONLY-LOCAL6 rule 21 description 'NTP'
set firewall ipv6 name V6ONLY-LOCAL6 rule 21 protocol 'udp'
set firewall ipv6 name V6ONLY-LOCAL6 rule 21 destination port '123'
set firewall ipv6 name V6ONLY-LOCAL6 rule 21 action 'accept'
set firewall ipv6 name V6ONLY-LOCAL6 rule 22 description 'NDP/RS/RA + icmp'
set firewall ipv6 name V6ONLY-LOCAL6 rule 22 protocol 'ipv6-icmp'
set firewall ipv6 name V6ONLY-LOCAL6 rule 22 action 'accept'
```

### 1o. Zones — bind interfaces + attach the chains
Any from-zone without an explicit ruleset falls through to the destination zone's
`default-action drop` (silent) — exactly the policy's "intentionally unlisted
pairs" (WAN→V6ONLY, V6ONLY↔DMZ/INTERNAL).
```sh
set firewall zone LOCAL local-zone
set firewall zone LOCAL default-action 'drop'
set firewall zone LOCAL from WAN firewall name 'WAN-LOCAL'
set firewall zone LOCAL from WAN firewall ipv6-name 'WAN-LOCAL6'
set firewall zone LOCAL from INTERNAL firewall name 'INTERNAL-LOCAL'
set firewall zone LOCAL from INTERNAL firewall ipv6-name 'INTERNAL-LOCAL6'
set firewall zone LOCAL from DMZ firewall name 'DMZ-LOCAL'
set firewall zone LOCAL from DMZ firewall ipv6-name 'DMZ-LOCAL6'
set firewall zone LOCAL from V6ONLY firewall ipv6-name 'V6ONLY-LOCAL6'

set firewall zone WAN interface 'eth0'
set firewall zone WAN default-action 'drop'
set firewall zone WAN from LOCAL firewall name 'LOCAL-OUT'
set firewall zone WAN from LOCAL firewall ipv6-name 'LOCAL-OUT6'
set firewall zone WAN from INTERNAL firewall name 'INTERNAL-WAN'
set firewall zone WAN from INTERNAL firewall ipv6-name 'INTERNAL-WAN6'
set firewall zone WAN from DMZ firewall name 'DMZ-WAN'
set firewall zone WAN from DMZ firewall ipv6-name 'DMZ-WAN6'
set firewall zone WAN from V6ONLY firewall ipv6-name 'V6ONLY-WAN6'

set firewall zone INTERNAL interface 'eth1'
set firewall zone INTERNAL default-action 'drop'
set firewall zone INTERNAL from LOCAL firewall name 'LOCAL-OUT'
set firewall zone INTERNAL from LOCAL firewall ipv6-name 'LOCAL-OUT6'
set firewall zone INTERNAL from WAN firewall name 'WAN-INTERNAL'
set firewall zone INTERNAL from WAN firewall ipv6-name 'WAN-INTERNAL6'
set firewall zone INTERNAL from DMZ firewall name 'DMZ-INTERNAL'
set firewall zone INTERNAL from DMZ firewall ipv6-name 'DMZ-INTERNAL6'

set firewall zone DMZ interface 'eth2'
set firewall zone DMZ default-action 'drop'
set firewall zone DMZ from LOCAL firewall name 'LOCAL-OUT'
set firewall zone DMZ from LOCAL firewall ipv6-name 'LOCAL-OUT6'
set firewall zone DMZ from WAN firewall name 'WAN-DMZ'
set firewall zone DMZ from WAN firewall ipv6-name 'WAN-DMZ6'
set firewall zone DMZ from INTERNAL firewall name 'INTERNAL-DMZ'
set firewall zone DMZ from INTERNAL firewall ipv6-name 'INTERNAL-DMZ6'

set firewall zone V6ONLY interface 'eth3'
set firewall zone V6ONLY default-action 'drop'
set firewall zone V6ONLY from LOCAL firewall ipv6-name 'LOCAL-OUT6'
```

### 1p. Commit with auto-rollback, verify, then persist
```sh
compare
commit-confirm 10
exit
```
Now **prove you still have access** — run the §2 tests, especially your own SSH
session and router internet. If anything broke, do nothing; it reverts in 10 min.
If all good:
```sh
configure
confirm
save
exit
```
Refresh the snapshot per repo convention:
```sh
# from your workstation
scp vyos@88.200.24.237:/config/config.boot vyos/snapshot-config.boot
```

---

## 2. Tests (N6.2)

Run from the indicated host. `nc -zv -w3` prints "succeeded" if open and times
out / "refused" if filtered (`apt install -y netcat-openbsd`).

### From the router (`kyber-rtr`) — LOCAL → any still works
```
# test on kyber-rtr — `ping -c1 8.8.8.8` — 0% packet loss (LOCAL-OUT)
# test on kyber-rtr — `ping6 -c1 2001:4860:4860::8888` — 0% packet loss
# test on kyber-rtr — `show firewall` — all zones present, each default-action drop
# test on kyber-rtr — `show log firewall name WAN-LOCAL` — bogon/default-drop hits logged
```

### From an external host (off-LAN) — WAN ingress
```
# test on external — `nmap -Pn -p22,443,9090,5432,2379 88.200.24.237`
#   expected: 443 open; 22 open ONLY while the TEMP rule exists; 9090/5432/2379 filtered
# test on external — `nmap -6 -Pn -p443,22 2001:1470:fffd:99::10`
#   expected: 443 open; 22 filtered
# test on external — `ssh vyos@88.200.24.237` — connects ONLY via TEMP rule 40 (gone after N7)
```

### From `ws-01` (internal, 10.7.0.0/24) — INTERNAL
```
# test on ws-01 — `curl -sf -o /dev/null -w '%{http_code}\n' https://api.kyber.local/customers` — 200 (INTERNAL→DMZ 443)
# test on ws-01 — `nc -zv -w3 192.168.7.10 22` — succeeded (INTERNAL→DMZ SSH)
# test on ws-01 — `dig +short api.kyber.local @10.7.0.1` — 192.168.7.10 (INTERNAL→LOCAL 53)
# test on ws-01 — `ping -c1 10.7.0.1` — 0% loss (INTERNAL→LOCAL icmp)
# test on ws-01 — `nc -zv -w3 192.168.7.10 5432` — timeout/refused (PostgreSQL blocked)
# test on ws-01 — `nc -zv -w3 192.168.7.20 9090` — timeout/refused (Prometheus blocked)
# test on ws-01 — `curl -s ifconfig.me` — 88.200.24.237 (INTERNAL→WAN ok)
```

### From `app-01` (DMZ, 192.168.7.10) — DMZ least-privilege
```
# test on app-01 — `curl -s ifconfig.me` — 88.200.24.237 (DMZ→WAN ok)
# test on app-01 — `chronyc sources` — shows 192.168.7.1 (DMZ→LOCAL NTP)
# test on app-01 — `dig +short api.kyber.local @192.168.7.1` — 192.168.7.10 (DMZ→LOCAL 53)
# test on app-01 — `nc -zv -w3 10.7.0.1 22` — timeout (DMZ→INTERNAL blocked + logged on rtr)
# test on app-01 — `nc -uzv -w3 192.168.7.1 161` — no response (SNMP denied: not mon)
```

### From `mon` (DMZ, 192.168.7.20) — SNMP source allow-list
```
# test on mon — `snmpwalk -v2c -c kyber-ro 192.168.7.1 ifNumber.0` — returns a value (N8)
```

### From `kyber-ipv6` (V6ONLY) — IPv6-only egress
```
# test on kyber-ipv6 — `curl -6 -sf -o /dev/null -w '%{http_code}\n' https://api.kyber.local` — 200 (V6ONLY→WAN via NPTv6)
# test on kyber-ipv6 — `dig +short AAAA api.kyber.local` — 2001:1470:fffd:99::10 (V6ONLY→LOCAL 53)
```

---

## 3. Follow-ups
- **N7:** add the `VPN` zone (`wg0`) per `firewall-policy.md`, then **delete the
  TEMP `WAN-LOCAL`/`WAN-LOCAL6` rule 40** (SSH becomes VPN-only).
- **I1:** WAN→DMZ 443 accept is already in place; add the DNAT
  (`88.200.24.237:443` → VIP `192.168.7.100:443`) + app-02/VIP DHCP reservations.
