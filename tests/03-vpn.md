# Test 03 — OpenVPN remote access + live FreeIPA auth (N7)

Validates the OpenVPN server on `vtun0` (snapshot `interfaces openvpn vtun0`, `pki ca-vpn`/
`srv-vpn`) and the live `openvpn-auth-ldap` gate against `vpn-users`. Mirrors the acceptance in
`vyos/08-openvpn-vpn.md` §9.

**Where to run:** an **off-LAN** client — a laptop on a phone hotspot, a cloud VM, or a home PC
that reaches `88.200.24.237:1194/udp` but is **not** on the internal/DMZ segments (the "remote
worker"). **Not** `ws-01`/`ws-02` or any DMZ host — those are already inside (if a box can `ssh` an
internal `10.7.0.x` host directly, it is not a valid vantage point). Plus `kyber-rtr` (§5) and
`kyber-ldap` (§6). **Set the client up first — see §0.** Covers **N7.1–N7.6**.

> As built: `udp/1194` on the WAN, split-tunnel (pushes `10.7.0.0/24`, `192.168.7.0/24`,
> `…9a::/64`, `…99::/64` — **not** a default route), tunnel `10.7.99.0/24` + `fd07:99::/64`,
> pushed DNS `10.7.99.1`, user auth = IPA username+password (`--verify-client-cert none`),
> authorization = live `vpn-users` membership in the LDAP `SearchFilter`.

---

## 0. Client setup (do this first)

**Pick the right machine** — an external box per "Where to run" above. ws-01/ws-02/DMZ hosts are
inside the LAN; the VPN grants access *to* those networks, so dialing in from one is meaningless.

**Install an OpenVPN client.** Linux: `sudo apt -y install openvpn`. Windows: OpenVPN Connect or the
Community GUI (openvpn.net), or `winget install OpenVPNTechnologies.OpenVPN`. There is **no `sudo`**
on Windows — use the GUI, or an **elevated** (Run as Administrator) terminal (OpenVPN needs admin to
add the tunnel route).

**Build the profile.** Copy `services/vpn/kyber.ovpn.example` → `kyber.ovpn` and paste the VyOS CA
into the `<ca>…</ca>` block. Router SSH is VPN-only (chicken-and-egg), so bootstrap the CA from the
**committed snapshot** rather than the box: take the `pki ca ca-vpn certificate "MIIDnz…"` base64 in
`vyos/snapshot-config.boot` and wrap it as `-----BEGIN CERTIFICATE-----` / `-----END CERTIFICATE-----`.
(With console or an existing VPN session: `show pki ca ca-vpn pem` on `kyber-rtr`.)

**Run it** (§1): Linux `sudo openvpn --config kyber.ovpn`; Windows — import the file into the GUI and
**Connect**, or elevated `& "C:\Program Files\OpenVPN\bin\openvpn.exe" --config kyber.ovpn`.

**Output:**
Works

## 1. Connect as an authorized user (N7.6)

**Run on:** the **external** client from §0 (Windows: import the `.ovpn` into the OpenVPN GUI and
Connect, or an **elevated** `openvpn.exe --config kyber.ovpn` — no `sudo`)

```
sudo openvpn --config kyber.ovpn          # prompts username/password; log in as alice (∈ vpn-users)
```

**Expect:** `Initialization Sequence Completed`; the client gets a `10.7.99.x` tunnel address and
an `fd07:99::x` address (`ip addr show tun0`).

**Output:**
Works

## 2. Authorization is enforced — non-member rejected

**Run on:** client

```
sudo openvpn --config kyber.ovpn          # log in as dave (∉ vpn-users)
```

**Expect:** `AUTH_FAILED` — the bind may succeed but the `memberOf=cn=vpn-users,…` clause in the
SearchFilter fails, so authorization is denied. (Confirms auth ≠ just a valid password.)

**Output:**
Works

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
