# 07 — Firewall (N6)

Zone-based encoding of `network/firewall-policy.md` on VyOS 1.4.4. Dual-stack.
Cite: **N6.1**, **N6.2**.

Each zone-pair `<FROM> → <TO>` is a named chain (`firewall ipv4|ipv6 name
<FROM>-<TO>`) attached with `zone <TO> from <FROM>`. The router is the `LOCAL`
local-zone. Stateful baseline is the global `state-policy` (already set on the box).

**Slim variant** — drops the optional hardening (bogon filtering, ICMP rate-limit,
per-type ICMPv6, HTTP/3, Kerberos) the brief doesn't require. The policy doc keeps
the full rationale; this is the lean thing to paste and test.

If a `set` line is rejected, tab-complete it on the box.

---

## 1. Apply (single atomic configure session)

```sh
configure
```

### 1a. Stateful baseline — ALREADY SET on the box, skip if present
```sh
set firewall global-options state-policy established action 'accept'
set firewall global-options state-policy related action 'accept'
set firewall global-options state-policy invalid action 'drop'
```

### 1b. LOCAL → any  (router-originated traffic always passes)
```sh
set firewall ipv4 name LOCAL-OUT default-action 'accept'
set firewall ipv6 name LOCAL-OUT6 default-action 'accept'
```

### 1c. WAN → LOCAL
```sh
set firewall ipv4 name WAN-LOCAL default-action 'drop'
set firewall ipv4 name WAN-LOCAL rule 20 protocol 'icmp'
set firewall ipv4 name WAN-LOCAL rule 20 icmp type-name 'echo-request'
set firewall ipv4 name WAN-LOCAL rule 20 action 'accept'
set firewall ipv4 name WAN-LOCAL rule 30 description 'OpenVPN endpoint (N7)'
set firewall ipv4 name WAN-LOCAL rule 30 protocol 'udp'
set firewall ipv4 name WAN-LOCAL rule 30 destination port '1194'
set firewall ipv4 name WAN-LOCAL rule 30 action 'accept'

set firewall ipv6 name WAN-LOCAL6 default-action 'drop'
set firewall ipv6 name WAN-LOCAL6 rule 20 description 'icmpv6 (echo + NDP)'
set firewall ipv6 name WAN-LOCAL6 rule 20 protocol 'ipv6-icmp'
set firewall ipv6 name WAN-LOCAL6 rule 20 action 'accept'
set firewall ipv6 name WAN-LOCAL6 rule 30 description 'OpenVPN endpoint (N7)'
set firewall ipv6 name WAN-LOCAL6 rule 30 protocol 'udp'
set firewall ipv6 name WAN-LOCAL6 rule 30 destination port '1194'
set firewall ipv6 name WAN-LOCAL6 rule 30 action 'accept'
```

### 1d. WAN → DMZ  (HTTPS only; external reach needs I1 DNAT)
```sh
set firewall ipv4 name WAN-DMZ default-action 'drop'
set firewall ipv4 name WAN-DMZ default-log
set firewall ipv4 name WAN-DMZ rule 20 description 'HTTPS / REST API'
set firewall ipv4 name WAN-DMZ rule 20 protocol 'tcp'
set firewall ipv4 name WAN-DMZ rule 20 destination port '443'
set firewall ipv4 name WAN-DMZ rule 20 action 'accept'

set firewall ipv6 name WAN-DMZ6 default-action 'drop'
set firewall ipv6 name WAN-DMZ6 default-log
set firewall ipv6 name WAN-DMZ6 rule 20 protocol 'tcp'
set firewall ipv6 name WAN-DMZ6 rule 20 destination port '443'
set firewall ipv6 name WAN-DMZ6 rule 20 action 'accept'
```

### 1e. WAN → INTERNAL  (drop everything, logged)
```sh
set firewall ipv4 name WAN-INTERNAL default-action 'drop'
set firewall ipv4 name WAN-INTERNAL default-log
set firewall ipv6 name WAN-INTERNAL6 default-action 'drop'
set firewall ipv6 name WAN-INTERNAL6 default-log
```

### 1f. INTERNAL → WAN  (unrestricted outbound; NAT in 04)
```sh
set firewall ipv4 name INTERNAL-WAN default-action 'accept'
set firewall ipv6 name INTERNAL-WAN6 default-action 'accept'
```

### 1g. INTERNAL → DMZ
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
# If you domain-join the clients (opt S9.1), also allow Kerberos:
# rule 32 tcp_udp 88 accept ; rule 33 tcp_udp 464 accept

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
```

### 1h. INTERNAL → LOCAL
```sh
set firewall ipv4 name INTERNAL-LOCAL default-action 'drop'
set firewall ipv4 name INTERNAL-LOCAL rule 20 description 'SSH mgmt'
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
set firewall ipv4 name INTERNAL-LOCAL rule 33 description 'ping gateway'
set firewall ipv4 name INTERNAL-LOCAL rule 33 protocol 'icmp'
set firewall ipv4 name INTERNAL-LOCAL rule 33 icmp type-name 'echo-request'
set firewall ipv4 name INTERNAL-LOCAL rule 33 action 'accept'

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
set firewall ipv6 name INTERNAL-LOCAL6 rule 32 description 'DHCPv6'
set firewall ipv6 name INTERNAL-LOCAL6 rule 32 protocol 'udp'
set firewall ipv6 name INTERNAL-LOCAL6 rule 32 destination port '547'
set firewall ipv6 name INTERNAL-LOCAL6 rule 32 action 'accept'
set firewall ipv6 name INTERNAL-LOCAL6 rule 33 description 'NDP + ping'
set firewall ipv6 name INTERNAL-LOCAL6 rule 33 protocol 'ipv6-icmp'
set firewall ipv6 name INTERNAL-LOCAL6 rule 33 action 'accept'
```

### 1i. DMZ → WAN  (updates/NTP/diag only)
```sh
set firewall ipv4 name DMZ-WAN default-action 'drop'
set firewall ipv4 name DMZ-WAN rule 20 description 'HTTP (apt)'
set firewall ipv4 name DMZ-WAN rule 20 protocol 'tcp'
set firewall ipv4 name DMZ-WAN rule 20 destination port '80'
set firewall ipv4 name DMZ-WAN rule 20 action 'accept'
set firewall ipv4 name DMZ-WAN rule 21 description 'HTTPS (apt/pip/CRL)'
set firewall ipv4 name DMZ-WAN rule 21 protocol 'tcp'
set firewall ipv4 name DMZ-WAN rule 21 destination port '443'
set firewall ipv4 name DMZ-WAN rule 21 action 'accept'
set firewall ipv4 name DMZ-WAN rule 22 description 'NTP fallback'
set firewall ipv4 name DMZ-WAN rule 22 protocol 'udp'
set firewall ipv4 name DMZ-WAN rule 22 destination port '123'
set firewall ipv4 name DMZ-WAN rule 22 action 'accept'
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
set firewall ipv6 name DMZ-WAN6 rule 23 protocol 'ipv6-icmp'
set firewall ipv6 name DMZ-WAN6 rule 23 action 'accept'
```

### 1j. DMZ → INTERNAL  (no lateral movement; logged)
```sh
set firewall ipv4 name DMZ-INTERNAL default-action 'drop'
set firewall ipv4 name DMZ-INTERNAL default-log
set firewall ipv6 name DMZ-INTERNAL6 default-action 'drop'
set firewall ipv6 name DMZ-INTERNAL6 default-log
```

### 1k. DMZ → LOCAL
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
set firewall ipv4 name DMZ-LOCAL rule 24 source address '192.168.7.20'
set firewall ipv4 name DMZ-LOCAL rule 24 action 'accept'
# NOTE: no SSH (tcp/22) from DMZ to the router — DMZ servers must not manage
# the router. Admin SSH to the router comes from INTERNAL (and VPN, N7) only.
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
set firewall ipv6 name DMZ-LOCAL6 rule 24 source address '2001:1470:fffd:99::20'
set firewall ipv6 name DMZ-LOCAL6 rule 24 action 'accept'
# NOTE: no SSH (tcp/22) from DMZ to the router — see IPv4 note above.
set firewall ipv6 name DMZ-LOCAL6 rule 26 protocol 'ipv6-icmp'
set firewall ipv6 name DMZ-LOCAL6 rule 26 action 'accept'
```

### 1l. V6ONLY → WAN / LOCAL
```sh
set firewall ipv6 name V6ONLY-WAN6 default-action 'accept'

set firewall ipv6 name V6ONLY-LOCAL6 default-action 'drop'
set firewall ipv6 name V6ONLY-LOCAL6 rule 20 protocol 'tcp_udp'
set firewall ipv6 name V6ONLY-LOCAL6 rule 20 destination port '53'
set firewall ipv6 name V6ONLY-LOCAL6 rule 20 action 'accept'
set firewall ipv6 name V6ONLY-LOCAL6 rule 21 protocol 'udp'
set firewall ipv6 name V6ONLY-LOCAL6 rule 21 destination port '123'
set firewall ipv6 name V6ONLY-LOCAL6 rule 21 action 'accept'
set firewall ipv6 name V6ONLY-LOCAL6 rule 22 protocol 'ipv6-icmp'
set firewall ipv6 name V6ONLY-LOCAL6 rule 22 action 'accept'
```
> V6ONLY → WAN is plain accept: the ruleset sees the pre-NPTv6 ULA source, and
> NPTv6 (02/04) translates the whole prefix on egress.

### 1m. Zones — bind interfaces + attach the chains
Any from-zone without a ruleset falls through to the destination zone's
`default-action drop` (WAN→V6ONLY, V6ONLY↔DMZ/INTERNAL — silent by design).
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

### 1n. Commit with auto-rollback, verify, then persist
```sh
compare
commit-confirm 10
exit
```
Run the §2 tests (especially your own SSH + router internet). If anything broke,
do nothing — it reverts in 10 min. If all good:
```sh
configure
confirm
save
exit
# from your workstation:
scp vyos@88.200.24.237:/config/config.boot vyos/snapshot-config.boot
```

---

## 2. Tests (N6.2)

`nc -zv -w3` prints "succeeded" if open, times out / "refused" if filtered.

```
# test on kyber-rtr  — `ping -c1 8.8.8.8`                              — 0% loss (LOCAL-OUT)
# test on kyber-rtr  — `show firewall`                                 — all zones, default-action drop
# test on external   — `nmap -Pn -p22,443,5432,9090 88.200.24.237`     — 22 open (TEMP), rest filtered
#   NB: 443 is filtered until I1 adds the DNAT — we scan the router's WAN IP (LOCAL zone), which has no :443.
# test on ws-01      — `curl -sf -o /dev/null -w '%{http_code}\n' https://api.kyber.local/customers` — 200
# test on ws-01      — `nc -zv -w3 192.168.7.10 5432`                  — timeout (PostgreSQL blocked)
# test on app-01     — `curl -4 -s ifconfig.me`                        — 88.200.24.237 (DMZ→WAN, v4 SNAT)
#   NB: plain `curl` may return ::10 — IPv6 has no NAT, the DMZ GUA is global. Use -4 to exercise the SNAT path.
# test on app-01     — `nc -zv -w3 <ws-01-lease-ip> 22`                — timeout (DMZ→INTERNAL blocked, logged)
# test on app-01     — `nc -zv -w3 10.7.0.1 22`                        — timeout (DMZ→router SSH denied)
# test on mon     — `snmpwalk -v2c -c kyber-ro 192.168.7.1 1.3.6.1.2.1.2.1.0` — returns ifNumber (N8)
#   NB: use the numeric OID; the `ifNumber.0` name needs IF-MIB installed on mon (snmp-mibs-downloader).
# test on kyber-ipv6 — `curl -6 -s https://ifconfig.co`                — returns 2001:1470:fffd:9b::… (V6ONLY→WAN, NPTv6)
#   NB: this is the NPTv6 EGRESS test (to the internet). The v6-only segment is isolated from DMZ by design,
#   so it cannot — and must not — reach the internal api.kyber.local (V6ONLY→DMZ falls through to drop).
```

---

## 3. Follow-ups
- **N7:** add the `VPN` zone (`vtun0`), then **delete the TEMP `WAN-LOCAL`/`WAN-LOCAL6`
  rule 40** (SSH becomes VPN-only).
- **I1:** add the DNAT (`88.200.24.237:443` → VIP `192.168.7.100:443`); WAN→DMZ 443
  accept is already here.
