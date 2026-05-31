# Configuration during OS installation

VM: `sk07-app-01` — host `kyber-app-01.kyber.local`, DMZ segment (`sk07-dmz` port group).
Roles (this runbook covers the first two): PostgreSQL primary + REST API instance 1.

OS: Ubuntu Server LTS — `ubuntu-24.04-live-server-amd64.iso` (any current LTS is fine;
the apt/netplan/systemd steps below are version-agnostic).

## 1. ESXi VM creation

- Guest OS: Linux → Ubuntu Linux (64-bit)
- vCPU 2, RAM 4 GB, disk 25 GB (thin), single NIC on port group **`sk07-dmz`**
- Boot the installer ISO.

App-01's NIC MAC is **`00:0c:29:a9:04:71`** (ESXi VM settings → Network adapter →
Advanced → MAC; also `ip link show ens160`). It keys the DHCP static reservations
below, and its DUID-LL form `00:03:00:01:00:0c:29:a9:04:71` keys the DHCPv6 one.
**If you ever rebuild this VM the MAC changes** — read the new one and replace every
occurrence. A wrong MAC silently breaks all addressing: the DMZ has no dynamic pool to
fall back on, so a non-matching reservation yields no lease at all (not even IPv6).

## 2. Networking (set during the Ubuntu installer, or via netplan post-install)

DMZ servers get their address from VyOS **via DHCP static reservation** (MAC→IP), not
manual static config — per the plan's "DHCP for servers" rule. App-01's reserved
addresses (pre-allocated in §0.5 / N3.2):

| Stack | Address | Method |
|---|---|---|
| IPv4 | `192.168.7.10/24`, GW `192.168.7.1` | DHCPv4 reservation (MAC→IP) |
| IPv6 | `2001:1470:fffd:99::10/64`, GW `2001:1470:fffd:99::1` | DHCPv6 stateful reservation (DUID) |

This host is **dual-stack** like every host in the lab: the REST API
must be reachable over IPv6 (S3.9), so leave both stacks enabled.

In the installer, accept DHCP for both IPv4 and IPv6. The resulting netplan
(`/etc/netplan/50-cloud-init.yaml`) should be:

```yaml
network:
  version: 2
  ethernets:
    ens160:
      dhcp4: true
      dhcp6: true
      accept-ra: true
      nameservers:
        search: [kyber.local]    # REQUIRED — routes *.local to unicast DNS (see §5 DNS note)
```

```
sudo netplan apply
ip -br addr show ens160     # expect 192.168.7.10 + 2001:1470:fffd:99::10
```

> **DHCPv6 DUID — required for the IPv6 address to be assigned.** The DMZ6 subnet has
> **no dynamic pool**; clients only get an address if their DHCPv6 **DUID** matches the
> reservation, which is keyed on a DUID-LL (`00:03:00:01:` + MAC). systemd-networkd's
> *default* DUID is **not** MAC-based, so out of the box it never matches and you get
> only a `fe80::` link-local — no global `2001:1470:fffd:99::10`. Force a DUID-LL:
>
> ```
> sudo tee /etc/systemd/networkd.conf >/dev/null <<'CONF'
> [DHCPv6]
> DUIDType=link-layer
> CONF
> sudo systemctl restart systemd-networkd
> sudo netplan apply
> ip -6 addr show ens160     # 2001:1470:fffd:99::10/128 (global) now present
> ```
>
> The DHCPv6-assigned global address is a `/128` (normal for stateful DHCPv6 — the on-link
> `/64` comes from the router advertisement). This step applies to **every dual-stack DMZ
> host** (app-02, mon); `kyber-ldap` does the equivalent on NetworkManager via
> `ipv6.dhcp-duid` (see `dmz-ldap/03-dhcpv6-prep.md`), not systemd-networkd.

- Hostname (installer "Your server's name"): `kyber-app-01`

## 3. First boot

```
sudo apt update && sudo apt -y full-upgrade
sudo reboot
```

## 4. Timezone + NTP (point at the VyOS relay, like the LDAP host)

Ubuntu Server uses `systemd-timesyncd`. Point it at the internal time source only —
the DMZ should not reach public NTP pools.

```
sudo timedatectl set-timezone Europe/Ljubljana
sudo sed -i 's/^#\?NTP=.*/NTP=192.168.7.1/' /etc/systemd/timesyncd.conf
sudo sed -i 's/^#\?FallbackNTP=.*/FallbackNTP=/' /etc/systemd/timesyncd.conf
sudo systemctl restart systemd-timesyncd
timedatectl show-timesync --property=ServerName    # -> 192.168.7.1
```

## 5. Sanity check

```
ping -c2 192.168.7.1                       # gateway
ping -c2 1.1.1.1                           # WAN
ping6 -c2 2001:1470:fffd:99::1             # IPv6 gateway
getent ahosts kyber-ldap.kyber.local       # both families -> 192.168.7.30 + 2001:1470:fffd:99::30
```

> **DNS note — `.local` + systemd-resolved gotcha.** DHCP hands out `192.168.7.1` (VyOS)
> as the resolver, and VyOS forwards `kyber.local` to FreeIPA (`.30`, repoint confirmed
> done). But systemd-resolved **reserves the `.local` TLD for multicast DNS and refuses
> to send `*.local` names to a unicast DNS server** — so without intervention
> `getent`/`resolvectl` fail with *"No appropriate name servers or networks for name
> found"* even though `dig @192.168.7.1 …` succeeds (dig bypasses resolved). The
> `nameservers.search: [kyber.local]` line in §2 fixes this by registering `kyber.local`
> as a **unicast routing domain** on the link; verify with `resolvectl status ens160`
> showing `DNS Domain: kyber.local`. This applies to every Ubuntu host on `kyber.local`
> (app-02, mon, ws-01).
>
> **If a `.local` name still won't resolve, check the FreeIPA host's firewall.**
> `kyber-ldap`'s firewalld must have the `dns` service (53/tcp+udp) open, plus the
> FreeIPA ports (80,443,389,636,88,464 tcp; 88,464 udp) for the later enrollment. A
> firewall **reject** shows up as `dig` reporting *"host unreachable"* against `.30:53`
> while plain `ping .30` still works. DNS (and those LDAP/Kerberos ports) must work
> before FreeIPA enrollment in `03-rest-api.md` §4.
