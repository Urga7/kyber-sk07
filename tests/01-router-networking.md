# Test 01 — Router base, NAT, NPTv6, DHCP, DNS, NTP, SNMP, NetFlow

Validates the core Track-N routing/services on `kyber-rtr` against
`vyos/snapshot-config.boot`. Firewall is tested separately ([`02-firewall.md`](02-firewall.md)),
VPN in [`03-vpn.md`](03-vpn.md).

**Where to run:** router commands need ESXi console or VPN (router SSH is VPN-only — see
[`README.md`](README.md) §1). Client-side checks run on a DMZ host (`kyber-app-01`) and an
internal host (`kyber-ws-01`). Covers **B3, B4, B5, N1, N2, N3, N4, N5, N8, N9**.

---

## 1. Base config & addressing (B3, B4)

**Run on:** `kyber-rtr`

```
show host name                       # -> kyber-rtr
show configuration | match domain  # domain-name kyber.local  (or: show configuration commands | match domain-name)
show interfaces ethernet eth0 | match inet   # 88.200.24.237/25 + 2001:1470:fffd:98::2/64
show interfaces ethernet eth1 | match inet   # 10.7.0.1/24      + 2001:1470:fffd:9a::1/64
show interfaces ethernet eth2 | match inet   # 192.168.7.1/24   + 2001:1470:fffd:99::1/64
show interfaces ethernet eth3 | match inet   # fd07:1:1:1::1/64 ONLY (no IPv4 — by design)
```

**Expect:** each interface carries exactly the address(es) above; **eth3 has no IPv4**.

```
show ip route 0.0.0.0/0      # static, next-hop 88.200.24.129 (eth0)
show ipv6 route ::/0         # static, next-hop 2001:1470:fffd:98::1 (eth0)
```

**Expect:** one default route per stack via the WAN gateway.

**Output:**
```
vyos@kyber-rtr:~$ show host name                       # -> kyber-rtr
kyber-rtrY (no IPv4 — by design)
vyos@kyber-rtr:~$ show configuration | match domain  # domain-name kyber.local  (or: show configuration commands | match domain-name)
                domain-name kyber.local
                domain-search kyber.local
                domain-search kyber.local
            domain 7.168.192.in-addr.arpa {
            domain kyber.local {
    domain-name kyber.local
vyos@kyber-rtr:~$ show interfaces ethernet eth0 | match inet   # 88.200.24.237/25 + 2001:1470:fffd:98::2/64
    inet 88.200.24.237/25 brd 88.200.24.255 scope global eth0
    inet6 2001:1470:fffd:98::2/64 scope global
    inet6 fe80::20c:29ff:fe07:4554/64 scope link
vyos@kyber-rtr:~$ show interfaces ethernet eth1 | match inet   # 10.7.0.1/24      + 2001:1470:fffd:9a::1/64
    inet 10.7.0.1/24 brd 10.7.0.255 scope global eth1
    inet6 2001:1470:fffd:9a::1/64 scope global
    inet6 fe80::20c:29ff:fe07:455e/64 scope link
vyos@kyber-rtr:~$ show interfaces ethernet eth2 | match inet   # 192.168.7.1/24   + 2001:1470:fffd:99::1/64
    inet 192.168.7.1/24 brd 192.168.7.255 scope global eth2
    inet6 2001:1470:fffd:99::1/64 scope global
    inet6 fe80::20c:29ff:fe07:4568/64 scope link
vyos@kyber-rtr:~$ show interfaces ethernet eth3 | match inet   # fd07:1:1:1::1/64 ONLY (no IPv4 — by design)
    inet6 fd07:1:1:1::1/64 scope global
    inet6 fe80::20c:29ff:fe07:4572/64 scope link
vyos@kyber-rtr:~$ show ip route 0.0.0.0/0      # static, next-hop 88.200.24.129 (eth0)
Routing entry for 0.0.0.0/0eth0)
  Known via "static", distance 1, metric 0, best
  Last update 09:34:10 ago
  * 88.200.24.129, via eth0, weight 1

vyos@kyber-rtr:~$ show ipv6 route ::/0         # static, next-hop 2001:1470:fffd:98::1 (eth0)
Routing entry for ::/0
  Known via "static", distance 1, metric 0, best
  Last update 09:34:14 ago
  * 2001:1470:fffd:98::1, via eth0, weight 1

vyos@kyber-rtr:~$
```

## 2. Baseline connectivity & forwarding (B5)

**Run on:** `kyber-rtr`

```
ping 8.8.8.8 count 3
ping 88.200.24.129 count 3
ping 2001:1470:fffd:98::1 count 3
ping 2001:4860:4860::8888 count 3
```

**Expect:** 0% loss on all four (IPv4 + IPv6 reach the gateway and the public internet).

**Output:**
Works (pings reply)

## 3. NAT44 source masquerade (N1)

**Run on:** `kyber-app-01` (DMZ) and `kyber-ws-01` (internal)

```
curl -4 https://ifconfig.me ; echo            # -> 88.200.24.237
```

**Expect:** both report the single public IPv4 `88.200.24.237` (DMZ rule 100, internal rule 110).

**Output:**
`88.200.24.237` on both.

**Run on:** `kyber-rtr` — confirm the translation is happening:

```
show nat source rules            # rule 100 (192.168.7.0/24) + rule 110 (10.7.0.0/24) -> masquerade, eth0
show nat source statistics       # packet/byte counters non-zero after the curls above
```

**Output:**
```
vyos@kyber-rtr:~$ show nat source rules            # rule 100 (192.168.7.0/24) + rule 110 (10.7.0.0/24) -> masquerade, eth0
Rule    Source          Destination    Proto    Out-Int    Translation
------  --------------  -------------  -------  ---------  -------------
100     192.168.7.0/24  0.0.0.0/0      any      eth0       masquerade
        sport any       dport any
110     10.7.0.0/24     0.0.0.0/0      any      eth0       masquerade
        sport any       dport any
vyos@kyber-rtr:~$ show nat source statistics       # packet/byte counters non-zero after the curls above
Rule    Packets    Bytes    Interface
------  ---------  -------  -----------
100     188        14272    eth0
110     59         9000     eth0
vyos@kyber-rtr:~$
```

## 4. NPTv6 — router side (N2)

> End-to-end NPTv6 from the IPv6-only host is in [`07-clients-and-ipv6only.md`](07-clients-and-ipv6only.md) §3.
> Here we confirm the rules exist and watch a translated packet leave eth0.

**Run on:** `kyber-rtr`

```
show nat66 source rules          # rule 10: source fd07:1:1:1::/64 -> 2001:1470:fffd:9b::/64 out eth0
show nat66 destination rules     # rule 10: dest 2001:1470:fffd:9b::/64 -> fd07:1:1:1::/64 in eth0
```

**Output:**
```
vyos@kyber-rtr:~$ show nat66 source rules          # rule 10: source fd07:1:1:1::/64 -> 2001:1470:fffd:9b::/64 out eth0
Rule    Source           Destination    Proto    Out-Int    Translation
------  ---------------  -------------  -------  ---------  ------------------------------------------------------
10      fd07:1:1:1::/64  ::/0           any      eth0       {'prefix': {'addr': '2001:1470:fffd:9b::', 'len': 64}}
        sport any        dport any
vyos@kyber-rtr:~$ show nat66 destination rules     # rule 10: dest 2001:1470:fffd:9b::/64 -> fd07:1:1:1::/64 in eth0
Rule    Source     Destination             Proto    In-Int    Translation
------  ---------  ----------------------  -------  --------  -----------------------------------------------
10      ::/0       2001:1470:fffd:9b::/64  any      eth0      {'prefix': {'addr': 'fd07:1:1:1::', 'len': 64}}
        sport any  dport any
vyos@kyber-rtr:~$
```

While a ping runs **from `kyber-ipv6`** (`ping6 2001:4860:4860::8888`), capture egress:

```
monitor traffic interface eth0 filter 'ip6 and icmp6'
```

**Expect:** the outbound echo-request's **source** is in `2001:1470:fffd:9b::/64` (the outer
prefix), **not** the inner ULA `fd07:1:1:1::…` — proving NPTv6 rewrote it (RFC 6296).

**Output:**
```
tcpdump: verbose output suppressed, use -v[v]... for full protocol decode
listening on eth0, link-type EN10MB (Ethernet), snapshot length 262144 bytes
21:44:23.727977 IP6 2001:1470:fffd:9b:20c:29ff:fe3a:af59 > 2001:4860:4860::8888: ICMP6, echo request, id 1, seq 8, length 64
21:44:23.735913 IP6 2001:4860:4860::8888 > 2001:1470:fffd:9b:20c:29ff:fe3a:af59: ICMP6, echo reply, id 1, seq 8, length 64
21:44:24.729367 IP6 2001:1470:fffd:9b:20c:29ff:fe3a:af59 > 2001:4860:4860::8888: ICMP6, echo request, id 1, seq 9, length 64
21:44:24.737480 IP6 2001:4860:4860::8888 > 2001:1470:fffd:9b:20c:29ff:fe3a:af59: ICMP6, echo reply, id 1, seq 9, length 64
21:44:25.730869 IP6 2001:1470:fffd:9b:20c:29ff:fe3a:af59 > 2001:4860:4860::8888: ICMP6, echo request, id 1, seq 10, length 64
21:44:25.738804 IP6 2001:4860:4860::8888 > 2001:1470:fffd:9b:20c:29ff:fe3a:af59: ICMP6, echo reply, id 1, seq 10, length 64
21:44:26.732279 IP6 2001:1470:fffd:9b:20c:29ff:fe3a:af59 > 2001:4860:4860::8888: ICMP6, echo request, id 1, seq 11, length 64
21:44:26.733994 IP6 fe80::221:9bff:fefc:6aa4 > 2001:1470:fffd:98::2: ICMP6, neighbor solicitation, who has 2001:1470:fffd:98::2, length 32
21:44:26.734056 IP6 2001:1470:fffd:98::2 > fe80::221:9bff:fefc:6aa4: ICMP6, neighbor advertisement, tgt is 2001:1470:fffd:98::2, length 24
21:44:26.740315 IP6 2001:4860:4860::8888 > 2001:1470:fffd:9b:20c:29ff:fe3a:af59: ICMP6, echo reply, id 1, seq 11, length 64
21:44:26.763866 IP6 fe80::221:9bff:fefc:6aa4 > fe80::20c:29ff:fe07:4554: ICMP6, neighbor solicitation, who has fe80::20c:29ff:fe07:4554, length 32
21:44:26.763930 IP6 fe80::20c:29ff:fe07:4554 > fe80::221:9bff:fefc:6aa4: ICMP6, neighbor advertisement, tgt is fe80::20c:29ff:fe07:4554, length 24
21:44:27.733752 IP6 2001:1470:fffd:9b:20c:29ff:fe3a:af59 > 2001:4860:4860::8888: ICMP6, echo request, id 1, seq 12, length 64
21:44:27.741772 IP6 2001:4860:4860::8888 > 2001:1470:fffd:9b:20c:29ff:fe3a:af59: ICMP6, echo reply, id 1, seq 12, length 64
21:44:28.735226 IP6 2001:1470:fffd:9b:20c:29ff:fe3a:af59 > 2001:4860:4860::8888: ICMP6, echo request, id 1, seq 13, length 64
21:44:28.743329 IP6 2001:4860:4860::8888 > 2001:1470:fffd:9b:20c:29ff:fe3a:af59: ICMP6, echo reply, id 1, seq 13, length 64
21:44:29.736928 IP6 2001:1470:fffd:9b:20c:29ff:fe3a:af59 > 2001:4860:4860::8888: ICMP6, echo request, id 1, seq 14, length 64
21:44:29.745010 IP6 2001:4860:4860::8888 > 2001:1470:fffd:9b:20c:29ff:fe3a:af59: ICMP6, echo reply, id 1, seq 14, length 64
21:44:31.997879 IP6 fe80::20c:29ff:fe07:4554 > fe80::221:9bff:fefc:6aa4: ICMP6, neighbor solicitation, who has fe80::221:9bff:fefc:6aa4, length 32
21:44:31.998071 IP6 fe80::221:9bff:fefc:6aa4 > fe80::20c:29ff:fe07:4554: ICMP6, neighbor advertisement, tgt is fe80::221:9bff:fefc:6aa4, length 24
^C
20 packets captured
20 packets received by filter
0 packets dropped by kernel
vyos@kyber-rtr:~$
```

## 5. DHCP / DHCPv6 / SLAAC + Router Advertisements (N3)

The brief: servers always get the **same** IP but **via DHCP** (reservation), users get dynamic
leases. As built: DHCPv6-stateful on internal+DMZ, SLAAC on the ipv6-only segment.

**Run on:** `kyber-rtr`

```
show dhcp server leases          # DMZ: app-01=.10 app-02=.11 mon=.20 ldap=.30 (from reservations);
                                 # INTERNAL: ws-01/ws-02 in 10.7.0.100-.200 (dynamic)
show dhcpv6 server leases        # DMZ6 reservations ::10/::11/::20/::30; INTERNAL6 in 9a::100-1ff
```

**Expect:** internal clients hold dynamic-pool addresses *here*. The DMZ reservations (MAC→IP for
v4, DUID-LL `00:03:00:01:`+MAC for v6) are honoured, but the DMZ subnet has **no dynamic range**, so
they are Kea **host reservations, not leases** and do **not** appear in this view — the host-side
check below is the proof.

**Output:**
```
vyos@kyber-rtr:~$ show dhcp server leases          # DMZ: app-01=.10 app-02=.11 mon=.20 ldap=.30 (from reservations);
IP Address    MAC address        State    Lease start          Lease expiration     Remaining    Pool      Hostname     Origin
------------  -----------------  -------  -------------------  -------------------  -----------  --------  -----------  --------
10.7.0.100    00:0c:29:9c:df:2f  free     2026/06/01 13:15:03  2026/06/02 13:15:03  -            INTERNAL               local
10.7.0.101    00:0c:29:cc:0d:8c  active   2026/06/02 18:05:36  2026/06/03 18:05:36  20:20:09     INTERNAL  kyber-ws-01  local
vyos@kyber-rtr:~$                                  # INTERNAL: ws-01/ws-02 in 10.7.0.100-.200 (dynamic)
vyos@kyber-rtr:~$ show dhcpv6 server leases
IPv6 address            State    Last communication    Lease expiration     Remaining    Type           Pool       DUID
----------------------  -------  --------------------  -------------------  -----------  -------------  ---------  -----------------------------------------------------
2001:1470:fffd:9a::1b7  expired  2026/06/01 13:15:04   2026/06/02 01:15:04  -            non-temporary             00:01:00:01:31:85:bf:7f:00:0c:29:9c:df:2f
2001:1470:fffd:9a::1ef  active   2026/06/02 18:05:38   2026/06/03 06:05:38  8:20:09      non-temporary  INTERNAL6  00:04:fe:ef:51:e5:97:8e:71:80:44:c1:63:71:30:f4:3e:4b
vyos@kyber-rtr:~$
```

**Run on:** `kyber-app-01` (reserved DMZ host) — prove the address came from DHCP, not static:

```
ip -br addr show ens160          # 192.168.7.10/24 + 2001:1470:fffd:99::10/64
networkctl status ens160                    # Address: "192.168.7.10 (DHCP4 via 192.168.7.1)" + DHCP6 DUID-LL = reservation via DHCP, not static
```

**Expect:** `.10` / `::10` present and obtained via DHCP (not a hard-coded netplan static).

**Output:**
```
kyber@kyber-app-01:~$ ip -br addr show ens160
ens160           UP             192.168.7.10/24 metric 100 192.168.7.100/24 2001:1470:fffd:99::100/64 2001:1470:fffd:99::10/128 fe80::20c:29ff:fea9:471/64
kyber@kyber-app-01:~$ resolvectl status ens160 | grep -i dhcp
kyber@kyber-app-01:~$ networkctl status ens160
● 2: ens160
                   Link File: /usr/lib/systemd/network/99-default.link
                Network File: /run/systemd/network/10-netplan-ens160.network
                       State: routable (configured)
                Online state: online
                        Type: ether
                        Path: pci-0000:03:00.0
                      Driver: vmxnet3
                      Vendor: VMware
                       Model: VMXNET3 Ethernet Controller
           Alternative Names: enp3s0
            Hardware Address: 00:0c:29:a9:04:71 (VMware, Inc.)
                         MTU: 1500 (min: 60, max: 9190)
                       QDisc: mq
IPv6 Address Generation Mode: eui64
    Number of Queues (Tx/Rx): 2/2
            Auto negotiation: no
                       Speed: 10Gbps
                      Duplex: full
                        Port: tp
                     Address: 192.168.7.10 (DHCP4 via 192.168.7.1)
                              192.168.7.100
                              2001:1470:fffd:99::10
                              2001:1470:fffd:99::100
                              fe80::20c:29ff:fea9:471
                     Gateway: 192.168.7.1
                              fe80::20c:29ff:fe07:4568
                         DNS: 192.168.7.1
                              2001:1470:fffd:99::1
              Search Domains: kyber.local
                         NTP: 192.168.7.1
                              2001:1470:fffd:99::1
           Activation Policy: up
         Required For Online: yes
             DHCP4 Client ID: IAID:0x9f6e8524/DUID
           DHCP6 Client IAID: 0x9f6e8524
           DHCP6 Client DUID: DUID-LL:0001000c29a90471
```

**SLAAC vs DHCPv6 split** — confirm the RA flags differ per segment:

```
show configuration commands | match router-advert
```

**Expect:** `eth1`/`eth2` carry `managed-flag` + `no-autonomous-flag` (→ stateful DHCPv6);
`eth3` advertises `fd07:1:1:1::/64` **autonomously** (→ SLAAC). Satisfies "≥1 SLAAC, ≥1 DHCPv6".

**Output:**
```
vyos@kyber-rtr:~$ show configuration commands | match router-advert
set service router-advert interface eth1 managed-flag
set service router-advert interface eth1 other-config-flag
set service router-advert interface eth1 prefix 2001:1470:fffd:9a::/64 no-autonomous-flag
set service router-advert interface eth2 managed-flag
set service router-advert interface eth2 other-config-flag
set service router-advert interface eth2 prefix 2001:1470:fffd:99::/64 no-autonomous-flag
set service router-advert interface eth3 default-preference 'medium'
set service router-advert interface eth3 dnssl 'kyber.local'
set service router-advert interface eth3 name-server 'fd07:1:1:1::1'
set service router-advert interface eth3 other-config-flag
set service router-advert interface eth3 prefix fd07:1:1:1::/64
vyos@kyber-rtr:~$
```

## 6. DNS forwarding + split DNS (N4)

**Run on:** `kyber-ws-01` (internal, resolver = `10.7.0.1`)

```
dig +short api.kyber.local              # -> 192.168.7.100  (private VIP, internal split answer)
dig +short AAAA api.kyber.local         # -> 2001:1470:fffd:99::100
dig +short kyber-ldap.kyber.local       # -> 192.168.7.30
dig +short -x 192.168.7.30              # -> kyber-ldap.kyber.local.  (reverse zone 7.168.192.in-addr.arpa)
dig +short vyos.net                     # -> a public IP (external names still resolve upstream)
```

**Expect:** internal names resolve to **private** DMZ addresses (split DNS); public names resolve
normally. The `kyber.local` zone is forwarded to FreeIPA (`192.168.7.30` / `…99::30`).

**Output:**
```
kyber@kyber-ws-01:~$ dig +short api.kyber.local              # -> 192.168.7.100  (private VIP, internal split answer)
dig +short AAAA api.kyber.local         # -> 2001:1470:fffd:99::100
dig +short kyber-ldap.kyber.local       # -> 192.168.7.30
dig +short -x 192.168.7.30              # -> kyber-ldap.kyber.local.  (reverse zone 7.168.192.in-addr.arpa)
dig +short vyos.net                     # -> a public IP (external names still resolve upstream)
192.168.7.100
2001:1470:fffd:99::100
192.168.7.30
kyber-ldap.kyber.local.
172.67.168.41
104.21.38.158
kyber@kyber-ws-01:~$
```

**Run on:** `kyber-rtr` — the router's own lookups use the same split forwarder:

```
dig kyber-ldap.kyber.local +short       # -> 192.168.7.30   (router self-resolution, needed by N7)
show dns forwarding statistics          # cache/forward counters increasing
```

**Output:**
```
vyos@kyber-rtr:~$ dig kyber-ldap.kyber.local +short       # -> 192.168.7.30   (router self-resolution, needed by N7)
arding statistics          # cache/forward counters increasing192.168.7.30
vyos@kyber-rtr:~$ show dns forwarding statistics          # cache/forward counters increasing
Cache entries    Max cache entries    Cache size
---------------  -------------------  ------------
187              10000                55.03 kbytes
vyos@kyber-rtr:~$
```

## 7. NTP relay (N5)

**Run on:** `kyber-app-01` (DMZ) — its chrony was pointed at the router

```
chronyc sources                          # the row for 192.168.7.1 is a selected source (^* or ^+)
chronyc tracking                         # "Reference ID" / "Leap status: Normal"
```

**Expect:** the router (`192.168.7.1` on DMZ, `10.7.0.1` on internal) should appear as a selected
source (`^*`/`^+`).

> **Finding (this run):** `kyber-app-01` instead syncs to **public pool servers** — `192.168.7.1`
> is absent from `chronyc sources`. DHCP advertises it (see §5 `networkctl` → `NTP: 192.168.7.1`),
> but the host's chrony isn't consuming it. Confirm the **relay itself** answers with a direct probe
> — `sudo chronyd -Q -t 3 'server 192.168.7.1 iburst'` (prints an offset line iff the VyOS relay
> responds) — then point the client at it (`server 192.168.7.1 iburst` in `/etc/chrony/chrony.conf`,
> or enable DHCP-sourced NTP) so N5.2 is actually demonstrated.

The router upstreams to `ntp1/ntp2.arnes.si` + pool servers (snapshot `service ntp`).

**Output:**

```
kyber@kyber-app-01:~$ chronyc sources                          # the row for 192.168.7.1 is a selected source (^* or ^+)
chronyc tracking                         # "Reference ID" / "Leap status: Normal"
MS Name/IP address         Stratum Poll Reach LastRx Last sample
===============================================================================
^+ prod-ntp-5.ntp4.ps5.cano>     2  10   377   635    -10ms[  -10ms] +/-   30ms
^- prod-ntp-4.ntp1.ps5.cano>     2  10   377   930  -9391us[-9656us] +/-   30ms
^- alphyn.canonical.com          2  10   377   32m  -2777us[-2910us] +/-   82ms
^- prod-ntp-3.ntp4.ps5.cano>     2  10   377   551  -9317us[-9302us] +/-   30ms
^- dataway.ch                    2  10   377   802  +3944us[+3678us] +/-   54ms
^- 83-215-130-11.dyn.cablel>     2  10   377   802  +5298us[+5032us] +/-   44ms
^* dns.3eck.net                  2   8   377   201  +3333us[+3328us] +/- 9836us
^- ch-ntp01.10g.ch               2  10   377   900  +5876us[+5611us] +/-   34ms
Reference ID    : 3E0CA76D (dns.3eck.net)
Stratum         : 3
Ref time (UTC)  : Tue Jun 02 19:47:16 2026
System time     : 0.000464336 seconds fast of NTP time
Last offset     : -0.000005078 seconds
RMS offset      : 0.000415251 seconds
Frequency       : 5.439 ppm slow
Residual freq   : -0.000 ppm
Skew            : 0.021 ppm
Root delay      : 0.019122131 seconds
Root dispersion : 0.000558778 seconds
Update interval : 258.2 seconds
Leap status     : Normal
kyber@kyber-app-01:~$
```

## 8. SNMP agent, source-restricted to mon (N8)

**Run on:** `kyber-mon` (the only authorized poller)

```
snmpwalk -v2c -c kyber-ro 192.168.7.1 .1.3.6.1.2.1.2.2.1.2      # lists eth0..eth3
snmpwalk -v2c -c kyber-ro 2001:1470:fffd:99::1 .1.3.6.1.2.1.1.5.0   # sysName -> kyber-rtr (v6 path)
```

**Expect:** interface table + system info returned (N8.3 acceptance).

**Negative — run on any non-mon host (e.g. `kyber-app-01`):**

```
snmpwalk -v2c -c kyber-ro 192.168.7.1 .1.3.6.1.2.1.1.1.0 -t 3    # Timeout: No Response
```

**Expect:** **timeout** — the `kyber-ro` community ACL is scoped to `192.168.7.20` /
`…99::20` only, so any other source is refused by design.

```
kyber@kyber-mon:~$ snmpwalk -v2c -c kyber-ro 192.168.7.1 .1.3.6.1.2.1.2.2.1.2      # lists eth0..eth3
snmpwalk -v2c -c kyber-ro 2001:1470:fffd:99::1 .1.3.6.1.2.1.1.5.0   # sysName -> kyber-rtr (v6 path)
iso.3.6.1.2.1.2.2.1.2.1 = STRING: "lo"
iso.3.6.1.2.1.2.2.1.2.2 = STRING: "eth0"
iso.3.6.1.2.1.2.2.1.2.3 = STRING: "eth1"
iso.3.6.1.2.1.2.2.1.2.4 = STRING: "eth2"
iso.3.6.1.2.1.2.2.1.2.5 = STRING: "eth3"
iso.3.6.1.2.1.2.2.1.2.6 = STRING: "pim6reg"
iso.3.6.1.2.1.2.2.1.2.7 = STRING: "vtun0"
iso.3.6.1.2.1.1.5.0 = STRING: "kyber-rtr"
kyber@kyber-mon:~$
```

## 9. NetFlow v9 export (N9)

> The collector/analysis side (ntopng) is in [`06-monitoring.md`](06-monitoring.md) §5.

**Run on:** `kyber-rtr`

```
show flow-accounting interface eth0      # live flow table: src/dst, ports, packets, bytes
```

Generate a little router-crossing traffic first (e.g. on `kyber-mon`:
`curl -o /dev/null https://speed.hetzner.de/100MB.bin`), then re-run.

**Expect:** rows appear for the eth0 flows; `show configuration commands | match flow-accounting`
confirms export to `192.168.7.20:2055`, `version 9`, `sampling-rate 1`, interfaces eth0–eth3.

**Output:**
Works as expected
