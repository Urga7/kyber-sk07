# DHCPv6 prep (LDAP VM — AlmaLinux 10)

Pre-stages a deterministic DHCPv6 client identity for `kyber-ldap`
(`192.168.7.30` / `2001:1470:fffd:99::30`) so the VyOS DHCPv6 reservation can
be written **before** IPv6 is ever brought up on the VM.

Project context: the dual-stack FreeIPA install is deferred until the router's
N3.4 (stateful DHCPv6 on eth2/DMZ) is in place — see `kyber-project-plan.md`
N3.4. IPv6 is currently `disabled` on `ens160` (`00-os-install-config.md`), so
there is no DUID being generated to read. Two more wrinkles make "just read the
DUID off the VM" the wrong approach here:

- AlmaLinux 10 uses **NetworkManager's internal DHCP client** — there is no
  `dhclient`, so no `/var/lib/dhclient/*.lease` file to grep.
- With `ipv6.method disabled` the DHCPv6 client never runs, so nothing emits a
  DUID until IPv6 is enabled.

So instead of chasing an auto-generated value, pin an explicit DUID. It is
known up front, survives reinstalls and lease wipes, and mirrors the MAC→IP
model already used for the DHCPv4 reservation.

## 1. Pin an explicit DUID on the VM

A DUID-LL is `00:03` (type = link-layer) + `00:01` (hw = Ethernet) + the
interface MAC. For `ens160` (`00:0c:29:82:fb:06`):

```
DUID = 00:03:00:01:00:0c:29:82:fb:06
```

Set it explicitly on the connection (an explicit hex string is more
deterministic than `ipv6.dhcp-duid ll`, which would *derive* the same value):

```
sudo nmcli con mod ens160 ipv6.dhcp-duid "00:03:00:01:00:0c:29:82:fb:06"
sudo nmcli con mod ens160 ipv6.dhcp-iaid mac
```

`ipv6.dhcp-iaid mac` is cosmetic here — VyOS/Kea matches the reservation on the
DUID (the `identifier` below), not the IAID — but pinning it keeps the
SOLICIT stable for debugging. Nothing takes effect yet; `ipv6.method` is still
`disabled` and stays that way until step 3.

Confirm it is stored on the connection profile:

```
nmcli -g ipv6.dhcp-duid con show ens160   # -> 00:03:00:01:00:0c:29:82:fb:06
```

## 2. Enable DHCPv6 on the VM (only once N3.4 is live)

Flip `ens160` from `disabled` to `auto`. With the N3.4 RA advertising
`managed-flag true`, `auto` performs stateful DHCPv6 for the address while
still taking the default route from the RA:

```
sudo nmcli con mod ens160 ipv6.method auto
sudo nmcli con up ens160
```

## 3. Verify

```
ip -6 addr show dev ens160                 # -> 2001:1470:fffd:99::30/64
ping -c2 2001:1470:fffd:99::1              # DMZ IPv6 gateway (eth2)
```
