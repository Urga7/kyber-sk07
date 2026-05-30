# kyber-ws-01 — OS install & network (Ubuntu Desktop, internal client)

VM: `kyber-ws-01` — internal segment (`sk07-internal` port group). Linux end-user client
satisfying the heterogeneous-OS requirement (S9.1), and the box used to run the REST API
acceptance suite in [`dmz-app-01/03-rest-api.md`](../dmz-app-01/03-rest-api.md) §6.
CA trust + the tests themselves are in [`01-ca-trust-and-acceptance.md`](01-ca-trust-and-acceptance.md).

OS: Ubuntu Desktop (current release; the NetworkManager/`resolvectl`/`update-ca-certificates`
steps below are version-agnostic).

## 1. ESXi VM creation

- Guest OS: Linux → Ubuntu Linux (64-bit)
- vCPU 2, RAM 4 GB, disk 25 GB (thin), single NIC on port group **`sk07-internal`**
- Boot the Ubuntu Desktop ISO, install normally, create a local user.
- Hostname (installer "computer name"): `kyber-ws-01`

## 2. Networking (DHCP, dual-stack)

Workstations are clients, **not** servers — they take a dynamic lease from the VyOS pools,
so there is **no DHCP static reservation** (the MAC→IP reservation rule applies only to DMZ
servers). The internal segment hands out:

| Stack | Source | Result |
|---|---|---|
| IPv4 | DHCPv4 pool `10.7.0.100`–`.200` | address + GW `10.7.0.1`, DNS `10.7.0.1`, domain `kyber.local` |
| IPv6 | DHCPv6 stateful pool `2001:1470:fffd:9a::100`–`::1ff` | address + DNS `2001:1470:fffd:9a::1`, search `kyber.local` |

Ubuntu Desktop uses **NetworkManager**, and its default wired profile already does DHCP for
both stacks — no config needed. Verify:

```
nmcli -g IP4.ADDRESS,IP6.ADDRESS device show ens160
ip -br addr show ens160     # expect a 10.7.0.x and a 2001:1470:fffd:9a::x global
```

> Unlike the DMZ hosts (`app-01`, `kyber-ldap`), the internal `/64` has a **dynamic DHCPv6
> pool**, so a global IPv6 is assigned to any client automatically — the
> `DUIDType=link-layer` workaround required on dual-stack DMZ hosts does **not** apply here.

## 3. DNS — make `kyber.local` resolve

DHCP gives `10.7.0.1` (VyOS) as the resolver, and VyOS forwards `kyber.local` to FreeIPA
(`.30`). Confirm the API name resolves to the **private** DMZ address (split-DNS, N4.3):

```
resolvectl status                   # Current DNS Server: 10.7.0.1 ; DNS Domain: kyber.local
resolvectl query api.kyber.local    # -> 192.168.7.10 and 2001:1470:fffd:99::10
```

> **`.local` + systemd-resolved gotcha.** If queries fail with *"No appropriate name servers
> or networks for name found"*, resolved is treating the `.local` TLD as multicast-DNS-only
> and refusing to send it to the unicast resolver. Register `kyber.local` as a unicast
> search/routing domain on the link (the NetworkManager equivalent of the netplan `search:`
> fix used on the servers):
>
> ```
> nmcli connection modify "Wired connection 1" ipv4.dns-search kyber.local ipv6.dns-search kyber.local
> nmcli connection up "Wired connection 1"
> ```
>
> (GUI equivalent: Settings → Network → wired ⚙ → IPv4 → "Additional search domains" =
> `kyber.local`.) Re-run `resolvectl status` and confirm it shows `DNS Domain: kyber.local`.

## 4. Time + sanity

```
sudo timedatectl set-timezone Europe/Ljubljana
ping  -c2 10.7.0.1                  # internal gateway
ping  -c2 1.1.1.1                   # WAN (internet reachability, S9.3)
ping6 -c2 2001:1470:fffd:9a::1      # IPv6 gateway
getent hosts api.kyber.local        # -> 192.168.7.10 / 2001:1470:fffd:99::10
```

Continue with [`01-ca-trust-and-acceptance.md`](01-ca-trust-and-acceptance.md).
