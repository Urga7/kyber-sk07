# Test 03 — OpenVPN remote access + live FreeIPA auth (N7)

Validates the OpenVPN server on `vtun0` (snapshot `interfaces openvpn vtun0`, `pki ca-vpn`/
`srv-vpn`) and the live `openvpn-auth-ldap` gate against `vpn-users`. Mirrors the acceptance in
`vyos/08-openvpn-vpn.md` §9.

**Where to run:** an **off-LAN** client (laptop on a phone hotspot / cloud VM) with the client
profile built from `services/vpn/kyber.ovpn.example` (CA block from `show pki ca ca-vpn pem`);
plus `kyber-rtr` and `kyber-ldap`. Covers **N7.1–N7.6**.

> As built: `udp/1194` on the WAN, split-tunnel (pushes `10.7.0.0/24`, `192.168.7.0/24`,
> `…9a::/64`, `…99::/64` — **not** a default route), tunnel `10.7.99.0/24` + `fd07:99::/64`,
> pushed DNS `10.7.99.1`, user auth = IPA username+password (`--verify-client-cert none`),
> authorization = live `vpn-users` membership in the LDAP `SearchFilter`.

---

## 1. Connect as an authorized user (N7.6)

**Run on:** client

```
sudo openvpn --config kyber.ovpn          # prompts username/password; log in as alice (∈ vpn-users)
```

**Expect:** `Initialization Sequence Completed`; the client gets a `10.7.99.x` tunnel address and
an `fd07:99::x` address (`ip addr show tun0`).

## 2. Authorization is enforced — non-member rejected

**Run on:** client

```
sudo openvpn --config kyber.ovpn          # log in as dave (∉ vpn-users)
```

**Expect:** `AUTH_FAILED` — the bind may succeed but the `memberOf=cn=vpn-users,…` clause in the
SearchFilter fails, so authorization is denied. (Confirms auth ≠ just a valid password.)

## 3. Tunnel reaches the company networks (split-tunnel scope)

**Run on:** client (connected as alice)

```
ping -c2 10.7.99.1                         # tunnel endpoint (router) — 0% loss
ping -c2 10.7.0.1                          # internal gateway via VPN->LOCAL
dig +short api.kyber.local                 # -> 192.168.7.100  (pushed DNS resolves to PRIVATE VIP)
curl -sf -o /dev/null -w '%{http_code}\n' --cacert dmz-ldap/kyber-ipa-ca.crt https://api.kyber.local/health   # 200 (VPN->DMZ 443)
ssh vyos@10.7.99.1                         # logs into the router (VPN->LOCAL 22)
```

**Expect:** internal + DMZ resources reachable; `api.kyber.local` resolves to the **internal** VIP
(pushed FreeIPA DNS), HTTPS to the API returns `200`.

## 4. It is a split tunnel, not a full tunnel

**Run on:** client (connected)

```
curl -s https://ifconfig.me ; echo        # -> the client's OWN public IP, NOT 88.200.24.237
```

**Expect:** general internet traffic does **not** egress through the company (only LAN/DMZ routes
are pushed). This is the deliberate split-tunnel design.

## 5. Server view

**Run on:** `kyber-rtr`

```
show openvpn server                        # connected clients listed with their tunnel IPs / usernames
show interfaces openvpn vtun0              # vtun0 up, server mode
```

**Expect:** the connected user (`alice`) appears with a `10.7.99.x` assignment.

## 6. Live revocation (offboarding = one IPA action, N7.4)

**Run on:** `kyber-ldap`, then reconnect on the client

```
# on kyber-ldap
kinit admin
ipa user-disable alice
# on the client: disconnect, then reconnect as alice
sudo openvpn --config kyber.ovpn
```

**Expect:** `AUTH_FAILED` — disabling the account (or removing from `vpn-users`) blocks the next
connect immediately, with no router/cert/peer changes. **Re-enable afterwards:**
`ipa user-enable alice`.

## 7. No raw SSH backdoor remains (the temp rule was removed)

**Run on:** external box (off-VPN)

```
nmap -Pn -p22 88.200.24.237                # filtered
```

**Expect:** `22/tcp filtered` — the temporary `WAN→LOCAL tcp/22` rule was deleted once the tunnel
worked (`08-openvpn-vpn.md` §7), so the router is reachable for management **only** through the VPN.
