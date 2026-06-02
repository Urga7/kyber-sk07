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

**Output:**
```
vyos@kyber-rtr:~$ show firewall > /tmp/firewall.out
vyos@kyber-rtr:~$ cat /tmp/firewall.out
Rulesets Information

---------------------------------
ipv4 Firewall "name DMZ-INTERNAL"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  drop      all                 0        0


---------------------------------
ipv4 Firewall "name DMZ-LOCAL"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------------------------------
20       accept    tcp_udp         26791  3428895  meta l4proto { tcp, udp } th dport 53  accept
21       accept    udp                78     5928  udp dport 123  accept
22       accept    udp                14     4673  udp dport 67  accept
24       accept    udp              1469   107231  udp dport 161 ip saddr 192.168.7.20  accept
26       accept    icmp                0        0  icmp type echo-request  accept
default  drop      all                 1       36


---------------------------------
ipv4 Firewall "name DMZ-WAN"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ------------------------------
20       accept    tcp                 0        0  tcp dport 80  accept
21       accept    tcp                11      660  tcp dport 443  accept
22       accept    udp              1316   100016  udp dport 123  accept
23       accept    icmp                0        0  icmp type echo-request  accept
default  drop      all                 2      186


---------------------------------
ipv4 Firewall "name INTERNAL-DMZ"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------------------------------
20       accept    tcp                 0        0  tcp dport 443  accept
21       accept    tcp                 0        0  tcp dport 22  accept
30       accept    tcp_udp             0        0  meta l4proto { tcp, udp } th dport 53  accept
31       accept    tcp                 0        0  tcp dport 636  accept
default  drop      all                 0        0


---------------------------------
ipv4 Firewall "name INTERNAL-LOCAL"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------------------------------
20       accept    tcp                 0        0  tcp dport 22  accept
30       accept    tcp_udp           137    11446  meta l4proto { tcp, udp } th dport 53  accept
31       accept    udp                41     3116  udp dport 123  accept
32       accept    udp                 1      323  udp dport 67  accept
33       accept    icmp                0        0  icmp type echo-request  accept
default  drop      all                 1       36


---------------------------------
ipv4 Firewall "name INTERNAL-WAN"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  accept    all               256    46560


---------------------------------
ipv4 Firewall "name LOCAL-OUT"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  accept    all             10900  4204944


---------------------------------
ipv4 Firewall "name VPN-DMZ"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------
20       accept    tcp                 1       52  tcp dport 443  accept
21       accept    tcp                 0        0  tcp dport 22  accept
22       accept    tcp                 0        0  tcp dport 636  accept
default  drop      all                 0        0


---------------------------------
ipv4 Firewall "name VPN-INTERNAL"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ------------------------------
20       accept    tcp                 0        0  tcp dport 22  accept
21       accept    tcp                 0        0  tcp dport 3389  accept
22       accept    icmp                0        0  icmp type echo-request  accept
default  drop      all                 0        0


---------------------------------
ipv4 Firewall "name VPN-LOCAL"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------------------------------
20       accept    tcp                83    17920  tcp dport 22  accept
30       accept    tcp_udp          1993   130426  meta l4proto { tcp, udp } th dport 53  accept
31       accept    udp                 0        0  udp dport 123  accept
default  drop      all                 0        0


---------------------------------
ipv4 Firewall "name WAN-DMZ"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------
20       accept    tcp               321    16948  tcp dport 443  accept
default  drop      all                 0        0


---------------------------------
ipv4 Firewall "name WAN-INTERNAL"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  drop      all                 0        0


---------------------------------
ipv4 Firewall "name WAN-LOCAL"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ------------------------------
20       accept    icmp               84     3128  icmp type echo-request  accept
30       accept    udp                34     1456  udp dport 1194  accept
default  drop      all              6652   385221


---------------------------------
ipv6 Firewall "name DMZ-INTERNAL6"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  drop      all                 0        0


---------------------------------
ipv6 Firewall "name DMZ-LOCAL6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  -----------------------------------------------------
20       accept    tcp_udp            59     6161  meta l4proto { tcp, udp } th dport 53  accept
21       accept    udp                69     6624  udp dport 123  accept
23       accept    udp                20     3147  udp dport 547  accept
24       accept    udp                 1       93  udp dport 161 ip6 saddr 2001:1470:fffd:99::20  accept
26       accept    ipv6-icmp        1189    79744  meta l4proto ipv6-icmp  accept
default  drop      all                 0        0


---------------------------------
ipv6 Firewall "name DMZ-WAN6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ------------------------------
20       accept    tcp                57     4560  tcp dport 80  accept
21       accept    tcp                88     7040  tcp dport 443  accept
22       accept    udp               273    26208  udp dport 123  accept
23       accept    ipv6-icmp           0        0  meta l4proto ipv6-icmp  accept
default  drop      all                26     2288


---------------------------------
ipv6 Firewall "name INTERNAL-DMZ6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------------------------------
20       accept    tcp                 0        0  tcp dport 443  accept
21       accept    tcp                 0        0  tcp dport 22  accept
30       accept    tcp_udp             0        0  meta l4proto { tcp, udp } th dport 53  accept
31       accept    tcp                 0        0  tcp dport 636  accept
default  drop      all                 0        0


---------------------------------
ipv6 Firewall "name INTERNAL-LOCAL6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------------------------------
20       accept    tcp                 0        0  tcp dport 22  accept
30       accept    tcp_udp             0        0  meta l4proto { tcp, udp } th dport 53  accept
31       accept    udp                40     3840  udp dport 123  accept
32       accept    udp                 2      302  udp dport 547  accept
33       accept    ipv6-icmp         231    15496  meta l4proto ipv6-icmp  accept
default  drop      all                 0        0


---------------------------------
ipv6 Firewall "name INTERNAL-WAN6"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  accept    all                70     5600


---------------------------------
ipv6 Firewall "name LOCAL-OUT6"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  accept    all              5886   489610


---------------------------------
ipv6 Firewall "name V6ONLY-LOCAL6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------------------------------
20       accept    tcp_udp            67     6588  meta l4proto { tcp, udp } th dport 53  accept
21       accept    udp                 0        0  udp dport 123  accept
22       accept    ipv6-icmp         102     6856  meta l4proto ipv6-icmp  accept
default  drop      all               143    12870


---------------------------------
ipv6 Firewall "name V6ONLY-WAN6"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  accept    all                50     4328


---------------------------------
ipv6 Firewall "name VPN-DMZ6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------
20       accept    tcp                93     6696  tcp dport 443  accept
21       accept    tcp                 0        0  tcp dport 22  accept
22       accept    tcp                 0        0  tcp dport 636  accept
default  drop      all                 0        0


---------------------------------
ipv6 Firewall "name VPN-INTERNAL6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ------------------------------
20       accept    tcp                 0        0  tcp dport 22  accept
21       accept    tcp                 0        0  tcp dport 3389  accept
22       accept    ipv6-icmp           0        0  meta l4proto ipv6-icmp  accept
default  drop      all                 0        0


---------------------------------
ipv6 Firewall "name VPN-LOCAL6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ---------------------------------------------
20       accept    tcp                 0        0  tcp dport 22  accept
30       accept    tcp_udp             0        0  meta l4proto { tcp, udp } th dport 53  accept
31       accept    udp                 0        0  udp dport 123  accept
default  drop      all                21     1512


---------------------------------
ipv6 Firewall "name WAN-DMZ6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ------------------------------------------------------
20       accept    tcp                 0        0  ip6 daddr 2001:1470:fffd:99::100 tcp dport 443  accept
default  drop      all                 1       72


---------------------------------
ipv6 Firewall "name WAN-INTERNAL6"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  drop      all                 0        0


---------------------------------
ipv6 Firewall "name WAN-LOCAL6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ------------------------------
20       accept    ipv6-icmp        2108   146200  meta l4proto ipv6-icmp  accept
30       accept    udp                 0        0  udp dport 1194  accept
default  drop      all                 0        0

```

## 2. WAN → LOCAL — router is closed except ICMP + VPN

**Run on:** external box

```
ping -c2 88.200.24.237                                  # replies (WAN-LOCAL rule 20)
nmap -Pn -p22,80,443 88.200.24.237                      # 22 filtered, 80 filtered, 443 ... see §4
nmap -Pn -sU -p1194 88.200.24.237                       # 1194/udp open|filtered (OpenVPN, rule 30)
```

**Expect:** **`tcp/22` is `filtered`** (no raw SSH from WAN — management is VPN-only). ICMP echo
answers; `udp/1194` is the only intentionally open router port.

**Output:**
```
@luka Γ₧£ ~ ping -c2 88.200.24.237                                  # replies (WAN-LOCAL rule 20)
PING 88.200.24.237 (88.200.24.237) 56(84) bytes of data.
64 bytes from 88.200.24.237: icmp_seq=1 ttl=52 time=7.39 ms
64 bytes from 88.200.24.237: icmp_seq=2 ttl=52 time=5.23 ms

--- 88.200.24.237 ping statistics ---
2 packets transmitted, 2 received, 0% packet loss, time 1002ms
rtt min/avg/max/mdev = 5.226/6.310/7.394/1.084 ms
@luka Γ₧£ ~ sudo nmap -Pn -p22,80,443 88.200.24.237                      # 22 filtered, 80 filtered, 443 ... see ┬º4
Starting Nmap 7.92 ( https://nmap.org ) at 2026-06-02 21:59 CEST
Nmap scan report for api.kyber.local (88.200.24.237)
Host is up (0.0085s latency).

PORT    STATE    SERVICE
22/tcp  filtered ssh
80/tcp  filtered http
443/tcp open     https

Nmap done: 1 IP address (1 host up) scanned in 1.42 seconds
@luka Γ₧£ ~ sudo nmap -Pn -sU -p1194 88.200.24.237                       # 1194/udp open|filtered (OpenVPN, rule 30)
Starting Nmap 7.92 ( https://nmap.org ) at 2026-06-02 22:00 CEST
Nmap scan report for api.kyber.local (88.200.24.237)
Host is up (0.0099s latency).

PORT     STATE SERVICE
1194/udp open  openvpn

Nmap done: 1 IP address (1 host up) scanned in 0.14 seconds

```

## 3. WAN → INTERNAL — never reachable

**Run on:** `kyber-rtr` — the internal prefix isn't internet-routable, so no external box can
generate traffic that reaches this pair. That unreachability *is* the control; assert the closed
posture on the router.

```
show firewall ipv4 name WAN-INTERNAL       # default-action drop, default-log, 0 pkts
show firewall ipv6 name WAN-INTERNAL6      # same, IPv6
```

**Expect:** both chains are `default-action drop` + `default-log` with **0 packets / 0 bytes**.
Zero counters are the **pass** — nothing from the WAN ever reaches the INTERNAL zone (the internet
has no route to `10.7.0.0/24` · `…9a::/64`; the firewall is the backstop if a route ever leaked).
The *identical* default-drop+log construct is shown actively firing and logging in §6
(DMZ→INTERNAL) — same mechanism, observed working; here there's simply no traffic to catch.

**Output:**
```
vyos@kyber-rtr:~$       show firewall ipv4 name WAN-INTERNAL      # default-action drop, default-log, 0 pkts
Ruleset Information

---------------------------------
ipv4 Firewall "name WAN-INTERNAL"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  drop      all                 0        0

vyos@kyber-rtr:~$       show firewall ipv6 name WAN-INTERNAL6
Ruleset Information

---------------------------------
ipv6 Firewall "name WAN-INTERNAL6"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  drop      all                 0        0

vyos@kyber-rtr:~$

```

## 4. WAN → DMZ — only HTTPS, only to the published endpoint

**Run on:** external box (after the I1 DNAT — see [`05-rest-api.md`](05-rest-api.md) §9)

```
nc -vz 88.200.24.237 443                  # open  (WAN-DMZ rule 20 -> DNAT to VIP .100)
nc -vz 88.200.24.237 22                   # refused/filtered (no SSH to DMZ from WAN)
```

**Expect (IPv4):** only `tcp/443` reaches the DMZ (DNAT'd to the VIP); everything else drops+logs.

**Output:**
```
@luka Γ₧£ ~ nc -vz 88.200.24.237 443                  # open  (WAN-DMZ rule 20 -> DNAT to VIP .100)
nc -vz 88.200.24.237 22                   # refused/filtered (no SSH to DMZ from WAN)
Ncat: Version 7.92 ( https://nmap.org/ncat )
Ncat: Connected to 88.200.24.237:443.
Ncat: 0 bytes sent, 0 bytes received in 0.01 seconds.
Ncat: Version 7.92 ( https://nmap.org/ncat )
Ncat: TIMEOUT.
```

**IPv6 — verify the perimeter is scoped to the API VIP (no NAT on v6):**

```
# on kyber-rtr
show firewall ipv6 name WAN-DMZ6
```

**Expect:** `WAN-DMZ6 rule 20` accepts `tcp/443` **only to the API VIP `2001:1470:fffd:99::100`**
(scoped per `vyos/10` §2b / `dmz-app-01/06`). Grafana/ntopng on `::20` and the FreeIPA Web UI on
`::30` fall through to `default-action drop` — they stay private over IPv6, matching
`network/firewall-policy.md` (dashboards / IPA UI are internal-only). Test the API path:
`curl -6 https://api.kyber.local/health` from outside → `200` (05 §9).

**Output:**
```
vyos@kyber-rtr:~$ show firewall ipv6 name WAN-DMZ6
Ruleset Information

---------------------------------
ipv6 Firewall "name WAN-DMZ6"

Rule     Action    Protocol      Packets    Bytes  Conditions
-------  --------  ----------  ---------  -------  ------------------------------------------------------
20       accept    tcp                 0        0  ip6 daddr 2001:1470:fffd:99::100 tcp dport 443  accept
default  drop      all                 1       72
```

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

**Output:**
```
kyber@kyber-ws-01:~$ curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health     # 200 (rule 20, tcp/443)
nc -vz 192.168.7.10 22                                                       # open (rule 21, SSH admin)
dig +short api.kyber.local @192.168.7.30 >/dev/null && echo dns-ok          # 53 to FreeIPA (rule 30)
nc -vz 192.168.7.30 636                                                      # open (rule 31, LDAPS)
nc -vz 192.168.7.20 9090
200
Connection to 192.168.7.10 22 port [tcp/ssh] succeeded!
dns-ok
Connection to 192.168.7.30 636 port [tcp/ldaps] succeeded!
TIMEOUT
```

## 6. DMZ → INTERNAL — lateral movement blocked

**Run on:** `kyber-app-01` (DMZ)

```
ping -c2 -W2 10.7.0.1                      # gateway answers? NO via DMZ->INTERNAL? see note
ssh -o ConnectTimeout=4 kyber@10.7.0.100   # hangs/timeout — DMZ cannot open new conns to INTERNAL

show log firewall ipv4 name DMZ-INTERNAL | tail      # the dropped SYN to 10.7.0.x, logged
show firewall ipv4 name DMZ-INTERNAL 
```

**Expect:** a DMZ server **cannot** initiate connections to internal workstations (`DMZ→INTERNAL`
default-drop+log, both stacks). This is the key containment control — verify it **fails**.


**Output:**
```
kyber@kyber-app-01:~$ ping -c2 -W2 10.7.0.1                      # gateway answers? NO via DMZ->INTERNAL? see note
ssh -o ConnectTimeout=4 kyber@10.7.0.100   # hangs/timeout ΓÇö DMZ cannot open new conns to INTERNAL
PING 10.7.0.1 (10.7.0.1) 56(84) bytes of data.
64 bytes from 10.7.0.1: icmp_seq=1 ttl=64 time=0.183 ms
64 bytes from 10.7.0.1: icmp_seq=2 ttl=64 time=0.252 ms

--- 10.7.0.1 ping statistics ---
2 packets transmitted, 2 received, 0% packet loss, time 1013ms
rtt min/avg/max/mdev = 0.183/0.217/0.252/0.034 ms
Connection timed out during banner exchange
Connection to UNKNOWN port 65535 timed out


vyos@kyber-rtr:~$ ping -c2 -W2 10.7.0.1                      # gateway answers NO via DMZ->INTERNAL see note
ping: Unknown host: -c2
vyos@kyber-rtr:~$ ssh -o ConnectTimeout=4 kyber@10.7.0.100   # hangs/timeout ΓÇö DMZ cannot open new conns to INTERNAL
ssh: connect to host 10.7.0.100 port 22: No route to host
vyos@kyber-rtr:~$ show log firewall ipv4 name DMZ-INTERNAL | tail      # the dropped SYN to 10.7.0.x, logged
Jun 02 22:20:45 kernel: [ipv4-NAM-DMZ-INTERNAL-default-D]IN=eth2 OUT=eth1 MAC=00:0c:29:07:45:68:00:0c:29:a9:04:71:08:00 SRC=192.168.7.10 DST=10.7.0.100 LEN=60 TOS=0x00 PREC=0x00 TTL=63 ID=29354 DF PROTO=TCP SPT=53270 DPT=22 WINDOW=64240 RES=0x00 SYN URGP=0
Jun 02 22:20:46 kernel: [ipv4-NAM-DMZ-INTERNAL-default-D]IN=eth2 OUT=eth1 MAC=00:0c:29:07:45:68:00:0c:29:a9:04:71:08:00 SRC=192.168.7.10 DST=10.7.0.100 LEN=60 TOS=0x00 PREC=0x00 TTL=63 ID=29355 DF PROTO=TCP SPT=53270 DPT=22 WINDOW=64240 RES=0x00 SYN URGP=0
Jun 02 22:20:47 kernel: [ipv4-NAM-DMZ-INTERNAL-default-D]IN=eth2 OUT=eth1 MAC=00:0c:29:07:45:68:00:0c:29:a9:04:71:08:00 SRC=192.168.7.10 DST=10.7.0.100 LEN=60 TOS=0x00 PREC=0x00 TTL=63 ID=29356 DF PROTO=TCP SPT=53270 DPT=22 WINDOW=64240 RES=0x00 SYN URGP=0
Jun 02 22:20:48 kernel: [ipv4-NAM-DMZ-INTERNAL-default-D]IN=eth2 OUT=eth1 MAC=00:0c:29:07:45:68:00:0c:29:a9:04:71:08:00 SRC=192.168.7.10 DST=10.7.0.100 LEN=60 TOS=0x00 PREC=0x00 TTL=63 ID=29357 DF PROTO=TCP SPT=53270 DPT=22 WINDOW=64240 RES=0x00 SYN URGP=0
Jun 02 23:04:33 kernel: [ipv4-NAM-DMZ-INTERNAL-default-D]IN=eth2 OUT=eth1 MAC=00:0c:29:07:45:68:00:0c:29:a9:04:71:08:00 SRC=192.168.7.10 DST=10.7.0.100 LEN=60 TOS=0x00 PREC=0x00 TTL=63 ID=56525 DF PROTO=TCP SPT=36832 DPT=22 WINDOW=64240 RES=0x00 SYN URGP=0
Jun 02 23:04:34 kernel: [ipv4-NAM-DMZ-INTERNAL-default-D]IN=eth2 OUT=eth1 MAC=00:0c:29:07:45:68:00:0c:29:a9:04:71:08:00 SRC=192.168.7.10 DST=10.7.0.100 LEN=60 TOS=0x00 PREC=0x00 TTL=63 ID=56526 DF PROTO=TCP SPT=36832 DPT=22 WINDOW=64240 RES=0x00 SYN URGP=0
Jun 02 23:04:35 kernel: [ipv4-NAM-DMZ-INTERNAL-default-D]IN=eth2 OUT=eth1 MAC=00:0c:29:07:45:68:00:0c:29:a9:04:71:08:00 SRC=192.168.7.10 DST=10.7.0.100 LEN=60 TOS=0x00 PREC=0x00 TTL=63 ID=56527 DF PROTO=TCP SPT=36832 DPT=22 WINDOW=64240 RES=0x00 SYN URGP=0
Jun 02 23:04:36 kernel: [ipv4-NAM-DMZ-INTERNAL-default-D]IN=eth2 OUT=eth1 MAC=00:0c:29:07:45:68:00:0c:29:a9:04:71:08:00 SRC=192.168.7.10 DST=10.7.0.100 LEN=60 TOS=0x00 PREC=0x00 TTL=63 ID=56528 DF PROTO=TCP SPT=36832 DPT=22 WINDOW=64240 RES=0x00 SYN URGP=0
vyos@kyber-rtr:~$ show firewall ipv4 name DMZ-INTERNAL
Ruleset Information

---------------------------------
ipv4 Firewall "name DMZ-INTERNAL"

Rule     Action    Protocol      Packets    Bytes
-------  --------  ----------  ---------  -------
default  drop      all                 4      240

kyber@kyber-app-01:~$ ping -c2 -W2 10.7.0.101                      # gateway answers? NO via DMZ->INTERNAL? see note
PING 10.7.0.101 (10.7.0.101) 56(84) bytes of data.

--- 10.7.0.101 ping statistics ---
2 packets transmitted, 0 received, 100% packet loss, time 1042ms



```
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

**Output:**
```
kyber@kyber-app-01:~$ dig +short vyos.net @192.168.7.1 >/dev/null && echo dns-ok    # rule 20 (53) ok
ping -c2 192.168.7.1                                           # rule 26 (icmp) ok
ssh -o ConnectTimeout=4 vyos@192.168.7.1                      # TIMEOUT ΓÇö DMZ may NOT SSH the router
dns-ok
PING 192.168.7.1 (192.168.7.1) 56(84) bytes of data.
64 bytes from 192.168.7.1: icmp_seq=1 ttl=64 time=0.175 ms
64 bytes from 192.168.7.1: icmp_seq=2 ttl=64 time=0.324 ms

--- 192.168.7.1 ping statistics ---
2 packets transmitted, 2 received, 0% packet loss, time 1062ms
rtt min/avg/max/mdev = 0.175/0.249/0.324/0.074 ms
Connection timed out during banner exchange
Connection to UNKNOWN port 65535 timed out

```

## 8. DMZ → WAN — restricted egress

**Run on:** `kyber-app-01`

```
curl -4 -s -o /dev/null -w '%{http_code}\n' https://deb.debian.org    # 200/3xx (rule 21, 443)
ping -c2 8.8.8.8                                                       # ok (rule 23, icmp)
nc -vz -w3 1.1.1.1 25                                                  # refused/timeout (SMTP not allowed)
```

**Expect:** 80/443/123/icmp egress works (updates, certs, NTP fallback); arbitrary outbound (e.g.
`tcp/25`) is dropped — limits blast radius if a DMZ host is compromised.

**Output:**
```
kyber@kyber-app-01:~$ curl -4 -s -o /dev/null -w '%{http_code}\n' https://deb.debian.org    # 200/3xx (rule 21, 443)
ping -c2 8.8.8.8                                                       # ok (rule 23, icmp)
nc -vz -w3 1.1.1.1 25                                                  # refused/timeout (SMTP not allowed)
200
PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
64 bytes from 8.8.8.8: icmp_seq=1 ttl=111 time=8.27 ms
64 bytes from 8.8.8.8: icmp_seq=2 ttl=111 time=8.36 ms

--- 8.8.8.8 ping statistics ---
2 packets transmitted, 2 received, 0% packet loss, time 1002ms
rtt min/avg/max/mdev = 8.267/8.315/8.364/0.048 ms
nc: connect to 1.1.1.1 port 25 (tcp) timed out: Operation now in progress
```

## 9. Outside scan — only intentional ports (N6.2 acceptance)

**Run on:** external box

```
sudo nmap -Pn  88.200.24.237
sudo nmap -Pn -sU -p1194 88.200.24.237
sudo nmap -6 -Pn 2001:1470:fffd:99::100        # the API VIP over IPv6
```

**Expect:** the only open ports are `tcp/443` (DMZ API via DNAT) and `udp/1194` (VPN). No `22`,
`9090`, `5432`, `3000`, `2379`, etc. From the DMZ, scanning the internal subnet is fully filtered
(§6).

> **IPv6 scan note:** the external vantage has no IPv6 transit to the GUA prefix (`setup_target:
> failed to determine route` in the output below), so the v6 perimeter can't be scanned from here.
> It's asserted on-router instead in §4 — `WAN-DMZ6` accepts `tcp/443` only to the API VIP `::100`,
> everything else default-drops. The v4 scan (`443` only) + `udp/1194` satisfy N6.2 for the v4 stack.

**Output:**
```
@luka Γ₧£ kyber-sk07 git(main) sudo nmap -Pn  88.200.24.237
sudo nmap -Pn -sU -p1194 88.200.24.237
sudo nmap -6 -Pn 2001:1470:fffd:99::100        # the API VIP over IPv6
Starting Nmap 7.92 ( https://nmap.org ) at 2026-06-02 22:59 CEST
Nmap scan report for api.kyber.local (88.200.24.237)
Host is up (0.0084s latency).
Not shown: 999 filtered tcp ports (no-response)
PORT    STATE SERVICE
443/tcp open  https

Nmap done: 1 IP address (1 host up) scanned in 6.67 seconds
Starting Nmap 7.92 ( https://nmap.org ) at 2026-06-02 22:59 CEST
Nmap scan report for api.kyber.local (88.200.24.237)
Host is up (0.0099s latency).

PORT     STATE SERVICE
1194/udp open  openvpn

Nmap done: 1 IP address (1 host up) scanned in 0.11 seconds
Starting Nmap 7.92 ( https://nmap.org ) at 2026-06-02 22:59 CEST
setup_target: failed to determine route to 2001:1470:fffd:99::100
WARNING: No targets were specified, so 0 hosts scanned.
Nmap done: 0 IP addresses (0 hosts up) scanned in 0.06 seconds

```
