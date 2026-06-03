# Test 07 — Heterogeneous clients + IPv6-only segment (S8, S9)

Validates the two end-user workstations (one Linux, one Windows — the heterogeneous-OS
requirement) and the IPv6-only host that demonstrates SLAAC + NPTv6. Mirrors
`ws-01/01-ca-trust-and-acceptance.md`, `ws-02/01-ca-trust-and-acceptance.md`, `ipv6/00-slaac-accept.md`.

**Where to run:** on each client / the IPv6-only host directly (they're on their own segments;
LAN clients need no VPN). Covers **S8.1–S8.2, S9.1–S9.3**.

---

## 1. `kyber-ws-01` — Ubuntu Desktop client (S9.1)

**Run on:** `kyber-ws-01`

```
ip -br addr show ens160                  # IPv4 10.7.0.x (dynamic lease) + IPv6 2001:1470:fffd:9a::x
dig +short api.kyber.local               # -> 192.168.7.100 (internal split-DNS answer)
curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health     # 200, no TLS warning (CA trusted)
curl -4 -s -o /dev/null -w '%{http_code}\n' https://deb.debian.org          # 200/3xx — internet works
```

**Expect:** dynamic v4 lease + v6 address (DHCPv6 stateful on internal); resolves internal names to
private IPs; reaches the DMZ API over a **trusted** HTTPS (the IPA CA is in the system store); has
internet access. Trust + HTTP/2 + auth detail is the full suite in
[`05-rest-api.md`](05-rest-api.md) (ws-01 is the reference Linux runner).

**Output:**
```
kyber@kyber-ws-01:~$ ip -br addr show ens160
ens160           UP             10.7.0.101/24 2001:1470:fffd:9a::1ef/128 fe80::20c:29ff:fecc:d8c/64
kyber@kyber-ws-01:~$ dig +short api.kyber.local
192.168.7.100
kyber@kyber-ws-01:~$ curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health
200
kyber@kyber-ws-01:~$ curl -4 -s -o /dev/null -w '%{http_code}\n' https://deb.debian.org
200
kyber@kyber-ws-01:~$
```

## 2. `kyber-ws-02` — Windows Server client (S9.2)

**Run on:** `kyber-ws-02` (PowerShell; call `curl.exe` explicitly, not the `curl` alias)

```powershell
ipconfig                                  # IPv4 10.7.0.x + IPv6 2001:1470:fffd:9a::x
Resolve-DnsName api.kyber.local           # -> 192.168.7.100 (+ ::100)
curl.exe -s -o NUL -w "%{http_code}`n" https://api.kyber.local/health     # 200 (CA in Schannel/Trusted Root)
Get-ChildItem Cert:\LocalMachine\Root | Where-Object { $_.Subject -like '*KYBER.LOCAL*' }   # CA present
```

**Expect:** a **Windows** client (heterogeneous-OS requirement satisfied: ws-01 Linux + ws-02
Windows) resolves internal DNS, trusts the FreeIPA CA in the machine Root store, and reaches the
API over HTTPS without warning. (HTTP/2 isn't asserted here — bundled `curl.exe`/Schannel lacks
nghttp2; verify `h2` from ws-01 or in Edge DevTools.)

**Output:**
```
administrator@KYBER-WS-02 C:\Users\Administrator>ipconfig

Windows IP Configuration


Ethernet adapter Ethernet0:

   Connection-specific DNS Suffix  . : kyber.local
   IPv6 Address. . . . . . . . . . . : 2001:1470:fffd:9a::1b7
   Link-local IPv6 Address . . . . . : fe80::31d4:cb58:897c:a682%6
   IPv4 Address. . . . . . . . . . . : 10.7.0.100
   Subnet Mask . . . . . . . . . . . : 255.255.255.0
   Default Gateway . . . . . . . . . : fe80::20c:29ff:fe07:455e%6
                                       10.7.0.1

administrator@KYBER-WS-02 C:\Users\Administrator>Resolve-DnsName api.kyber.local
'Resolve-DnsName' is not recognized as an internal or external command,
operable program or batch file.

administrator@KYBER-WS-02 C:\Users\Administrator>powershell.exe
Windows PowerShell
Copyright (C) Microsoft Corporation. All rights reserved.

Install the latest PowerShell for new features and improvements! https://aka.ms/PSWindows

PS C:\Users\Administrator> Resolve-DnsName api.kyber.local

Name                                           Type   TTL   Section    IPAddress
----                                           ----   ---   -------    ---------
api.kyber.local                                AAAA   36984 Answer     2001:1470:fffd:99::100
api.kyber.local                                A      36984 Answer     192.168.7.100


PS C:\Users\Administrator> curl.exe -s -o NUL -w "%{http_code}`n" https://api.kyber.local/health
200
PS C:\Users\Administrator> Get-ChildItem Cert:\LocalMachine\Root | Where-Object { $_.Subject -like '*KYBER.LOCAL*' }


   PSParentPath: Microsoft.PowerShell.Security\Certificate::LocalMachine\Root

Thumbprint                                Subject
----------                                -------
0E6A67A2EDF07D5BF36E3F64EA291D0F3A4A5EC2  CN=Certificate Authority, O=KYBER.LOCAL


PS C:\Users\Administrator>
```

> **Note — Schannel CRL revocation (found & fixed 2026-06-03).** On the first run this check
> returned `000`; `curl.exe -v` reported `0x80092013 CRYPT_E_REVOCATION_OFFLINE`. Windows/Schannel
> checks the cert's CRL by default, but internal clients had no path to the FreeIPA CDP
> (`http://ipa-ca.kyber.local/ipa/crl/MasterCRL.bin`, tcp/80 on `kyber-ldap`). ws-01 (Linux) passed
> because OpenSSL doesn't CRL-check by default. **Fix:** (1) `INTERNAL → DMZ` **rule 35** — tcp/80 to
> the CA host `.30`/`::30` only — in [`../vyos/07-firewall-setup.md`](../vyos/07-firewall-setup.md)
> §1g; (2) ensured `ipa-ca.kyber.local` resolves dual-stack (A `192.168.7.30` + AAAA
> `2001:1470:fffd:99::30`). The `200` above is now a *full* revocation-checked pass — no
> `--ssl-no-revoke`.

## 3. `kyber-ipv6` — IPv6-only segment via SLAAC + NPTv6 (S8)

**Run on:** `kyber-ipv6`

```
ip -br addr show ens160                  # a global fd07:1:1:1::… ULA (SLAAC) + link-local; NO IPv4 global
ip -4 addr show ens160 | grep 'inet '    # (empty) — no IPv4 stack, by design
ip -6 route | grep default               # default via fe80::… (VyOS eth3 link-local) dev ens160
```

**Expect:** exactly one global IPv6 (the SLAAC-derived ULA from `fd07:1:1:1::/64`) and **no IPv4**.

**Output:**
```
kyber@kyber-ipv6:~$ ip -br addr show ens160
ens160           UP             fd07:1:1:1:20c:29ff:fe3a:af59/64 fe80::20c:29ff:fe3a:af59/64
kyber@kyber-ipv6:~$ ip -br addr show ens160
ens160           UP             fd07:1:1:1:20c:29ff:fe3a:af59/64 fe80::20c:29ff:fe3a:af59/64
kyber@kyber-ipv6:~$ ip -6 route | grep default
default via fe80::20c:29ff:fe07:4572 dev ens160 proto ra metric 1024 expires 1777sec pref medium
kyber@kyber-ipv6:~$
```

```
# outbound internet over IPv6 — this is what NPTv6 is for (V6ONLY -> WAN)
ping6 -c3 2001:4860:4860::8888           # 0% loss
curl -6 -s -o /dev/null -w '%{http_code}\n' https://ipv6.google.com    # 200/3xx
curl -4 https://example.com              # FAILS — no IPv4 stack
```

**Expect:** IPv6 internet works (egress source-rewritten by NPTv6 to `2001:1470:fffd:9b::/64` —
observe it on the router per [`01-router-networking.md`](01-router-networking.md) §4); IPv4 fails.

```
# resolver reachability (V6ONLY -> LOCAL, dns)
dig +short AAAA ipv6.google.com          # resolves via the router fd07:1:1:1::1
```

**Output:**
```
kyber@kyber-ipv6:~$ ping6 -c3 2001:4860:4860::8888
PING 2001:4860:4860::8888(2001:4860:4860::8888) 56 data bytes
64 bytes from 2001:4860:4860::8888: icmp_seq=1 ttl=110 time=8.12 ms
64 bytes from 2001:4860:4860::8888: icmp_seq=2 ttl=110 time=8.29 ms
64 bytes from 2001:4860:4860::8888: icmp_seq=3 ttl=110 time=8.23 ms

--- 2001:4860:4860::8888 ping statistics ---
3 packets transmitted, 3 received, 0% packet loss, time 2003ms
rtt min/avg/max/mdev = 8.115/8.211/8.285/0.071 ms
kyber@kyber-ipv6:~$ curl -6 -s -o /dev/null -w '%{http_code}\n' https://ipv6.google.com
200
kyber@kyber-ipv6:~$ curl -4 https://example.com
curl: (7) Couldn't connect to server
kyber@kyber-ipv6:~$ dig +short AAAA ipv6.google.com
ipv6.l.google.com.
2a00:1450:400d:80b::200e
kyber@kyber-ipv6:~$
```

> **Deliberate isolation — do NOT expect DMZ access.** `api.kyber.local` resolves to the DMZ VIP
> `…99::100`, but the firewall has **no `V6ONLY→DMZ` (or `→INTERNAL`) pair** — both fall to
> default-drop (`network/firewall-policy.md`: "ipv6-only hosts communicate outbound via NPTv6 to
> WAN only"). So `curl -6 https://api.kyber.local` from this host **times out by design** — that is
> a pass, not a failure. The IPv6-only segment reaches the **router** (DNS/NTP/ICMP) and the
> **internet** (NPTv6), nothing lateral.

**Output:**
`curl -6 https://api.kyber.local` does indeed time out.