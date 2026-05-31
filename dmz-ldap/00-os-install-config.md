# Configuration during OS installation

OS: AlmaLinux 10.1 — `AlmaLinux-10.1-x86_64-minimal.iso`
MAC Address: 00:0c:29:82:fb:06

ens160 interface:
- IPv4 method: Automatic (DHCP) — VyOS DMZ-scope static reservation
  `00:0c:29:82:fb:06 → 192.168.7.30`
    - Installed initially as **Manual**, then switched to DHCP post-install per
      `ldap-setup.txt` flag #1 ("DHCP for servers"). Switch command:
      `sudo nmcli con mod ens160 ipv4.method auto ipv4.addresses "" ipv4.gateway "" ipv4.dns 1.1.1.1 ipv4.ignore-auto-dns yes ipv6.method disabled`
    - Effective addressing delivered by the reservation:
        - Subnet: 192.168.7.0/24
        - Address: 192.168.7.30
        - Gateway: 192.168.7.1
    - DNS pinned to 1.1.1.1 (`ipv4.ignore-auto-dns yes`) through FreeIPA prep;
      `ipa-server-install` later repoints resolv.conf at 127.0.0.1
- IPv6 method: Disabled **at install only** — FreeIPA installs cleanly v4-first, then `ens160`
  is flipped to dual-stack DHCPv6 (DMZ6 reservation → `2001:1470:fffd:99::30`) in
  [`03-dhcpv6-prep.md`](03-dhcpv6-prep.md) §2. End state: **dual-stack**, like every host.
- Hostname: `kyber-ldap.kyber.local`

After the first boot:

```
sudo dnf -y upgrade
sudo reboot
```
