# Test 02 — Zone-based dual-stack firewall (N6)

Validates the zone firewall in `vyos/snapshot-config.boot` against the rationale in
`network/firewall-policy.md`. Tests are **both directions**: a service that should be reachable
**and** a path that must be blocked. Every assertion below is encoded in the snapshot.

**Where to run:** the router (config/counters), an **external** box (off-LAN, off-VPN — phone
hotspot or cloud VM), a **DMZ** host (`kyber-app-01`), and an **internal** host (`kyber-ws-01`).
VPN-zone paths are covered in [`03-vpn.md`](03-vpn.md). Covers **N6**.

> **Zones (snapshot):** WAN=eth0, INTERNAL=eth1, DMZ=eth2, V6ONLY=eth3, VPN=vtun0,
> LOCAL=router. Default action every zone-pair: **drop**. Stateful established/related accept is
> a global state-policy.

---

## 1. Rulesets are loaded (sanity)

**Run on:** `kyber-rtr`

```
show firewall                            # zones + per-pair chains present, both stacks
show firewall ipv4 name WAN-LOCAL        # rule 20 icmp echo, rule 30 udp/1194; default drop
show firewall ipv6 name WAN-DMZ6         # rule 20 accept tcp/443; default drop+log
```

**Expect:** zones `WAN/INTERNAL/DMZ/V6ONLY/VPN/LOCAL` bound to their interfaces; named chains
match the snapshot.

## 2. WAN → LOCAL — router is closed except ICMP + VPN

**Run on:** external box

```
ping -c2 88.200.24.237                                  # replies (WAN-LOCAL rule 20)
nmap -Pn -p22,80,443 88.200.24.237                      # 22 filtered, 80 filtered, 443 ... see §4
nmap -Pn -sU -p1194 88.200.24.237                       # 1194/udp open|filtered (OpenVPN, rule 30)
```

**Expect:** **`tcp/22` is `filtered`** (no raw SSH from WAN — management is VPN-only). ICMP echo
answers; `udp/1194` is the only intentionally open router port.

## 3. WAN → INTERNAL — never reachable

**Run on:** external box

```
nmap -Pn -p22,443,3389 10.7.0.0/24        # (won't route publicly anyway; the firewall is the backstop)
```

**Run on:** `kyber-rtr` — prove the drop fires on the real path:

```
show log firewall name WAN-INTERNAL | tail        # default-drop is logged (default-log)
```

**Expect:** `WAN→INTERNAL` is total drop+log; the internal user segment is invisible from the
internet. Same for IPv6 (`WAN-INTERNAL6`).

## 4. WAN → DMZ — only HTTPS, only to the published endpoint

**Run on:** external box (after the I1 DNAT — see [`05-rest-api.md`](05-rest-api.md) §9)

```
nc -vz 88.200.24.237 443                  # open  (WAN-DMZ rule 20 -> DNAT to VIP .100)
nc -vz 88.200.24.237 22                   # refused/filtered (no SSH to DMZ from WAN)
```

**Expect (IPv4):** only `tcp/443` reaches the DMZ (DNAT'd to the VIP); everything else drops+logs.

**IPv6 — verify the actual snapshot state (do not assume the runbook narrowing):**

```
# on kyber-rtr
show firewall ipv6 name WAN-DMZ6
```

**Expect / check:** snapshot `WAN-DMZ6 rule 20` accepts `tcp/443` to the DMZ `/64`. Runbooks
`vyos/10` §2b and `dmz-app-01/06` describe narrowing this to **only** the API VIP
`2001:1470:fffd:99::100` (so Grafana/ntopng on `::20` stay private over v6) — **that narrowing
is not present in the current snapshot.** If keeping the dashboards off public IPv6 is required,
apply the `destination address 2001:1470:fffd:99::100` scoping and re-snapshot. Test the API path
either way: `curl -6 https://api.kyber.local/health` from outside → `200` (05 §9).

## 5. INTERNAL → DMZ — published services reachable

**Run on:** `kyber-ws-01`

```
curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health     # 200 (rule 20, tcp/443)
nc -vz 192.168.7.10 22                                                       # open (rule 21, SSH admin)
dig +short api.kyber.local @192.168.7.30 >/dev/null && echo dns-ok          # 53 to FreeIPA (rule 30)
nc -vz 192.168.7.30 636                                                      # open (rule 31, LDAPS)
nc -vz 192.168.7.20 9090                                                     # refused/timeout (Prometheus NOT exposed)
```

**Expect:** 443 / 22 / 53 / 636 succeed; Prometheus `9090`, PostgreSQL `5432`, SNMP `161` are
**not** reachable from internal (default-drop catch-all).

## 6. DMZ → INTERNAL — lateral movement blocked

**Run on:** `kyber-app-01` (DMZ)

```
ping -c2 -W2 10.7.0.1                      # gateway answers? NO via DMZ->INTERNAL? see note
ssh -o ConnectTimeout=4 kyber@10.7.0.100   # hangs/timeout — DMZ cannot open new conns to INTERNAL
```

**Expect:** a DMZ server **cannot** initiate connections to internal workstations (`DMZ→INTERNAL`
default-drop+log, both stacks). This is the key containment control — verify it **fails**.

> Note: `10.7.0.1` is the router's INTERNAL interface (LOCAL zone, not INTERNAL zone); pinging it
> exercises `DMZ→LOCAL` (allowed, §7), not `DMZ→INTERNAL`. To test `DMZ→INTERNAL`, target an
> actual internal host like `kyber-ws-01` (`10.7.0.x`) as in the `ssh` line above.

## 7. DMZ → LOCAL — only infra services, no SSH to router

**Run on:** `kyber-app-01`

```
dig +short vyos.net @192.168.7.1 >/dev/null && echo dns-ok    # rule 20 (53) ok
ping -c2 192.168.7.1                                           # rule 26 (icmp) ok
ssh -o ConnectTimeout=4 vyos@192.168.7.1                      # TIMEOUT — DMZ may NOT SSH the router
```

**Expect:** DNS/NTP/ICMP to the router work; **SSH from DMZ to the router is dropped** (the DMZ is
the most exposed segment — admin SSH comes from INTERNAL/VPN only). SNMP `udp/161` is allowed
**only from mon** (tested in [`01-router-networking.md`](01-router-networking.md) §8).

## 8. DMZ → WAN — restricted egress

**Run on:** `kyber-app-01`

```
curl -4 -s -o /dev/null -w '%{http_code}\n' https://deb.debian.org    # 200/3xx (rule 21, 443)
ping -c2 8.8.8.8                                                       # ok (rule 23, icmp)
nc -vz -w3 1.1.1.1 25                                                  # refused/timeout (SMTP not allowed)
```

**Expect:** 80/443/123/icmp egress works (updates, certs, NTP fallback); arbitrary outbound (e.g.
`tcp/25`) is dropped — limits blast radius if a DMZ host is compromised.

## 9. Outside scan — only intentional ports (N6.2 acceptance)

**Run on:** external box

```
nmap -Pn  88.200.24.237
nmap -Pn -sU -p1194 88.200.24.237
nmap -6 -Pn 2001:1470:fffd:99::100        # the API VIP over IPv6
```

**Expect:** the only open ports are `tcp/443` (DMZ API via DNAT) and `udp/1194` (VPN). No `22`,
`9090`, `5432`, `3000`, `2379`, etc. From the DMZ, scanning the internal subnet is fully filtered
(§6).
