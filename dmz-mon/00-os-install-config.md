# kyber-mon — OS install & network (Ubuntu Server, DMZ monitoring)

VM: `sk07-mon` — host `kyber-mon.kyber.local`, DMZ segment (`sk07-dmz` port group).
Roles (these runbooks cover the monitoring stack): **Prometheus + Grafana + exporters**
(S5). Other planned roles on this host — etcd node 3 (S4), and the optional ntopng /
Suricata — are documented in their own runbooks, not here.

OS: Ubuntu Server LTS — `ubuntu-24.04-live-server-amd64.iso` (any current LTS is fine;
the apt/netplan/systemd steps below are version-agnostic).

## 1. ESXi VM creation

- Guest OS: Linux → Ubuntu Linux (64-bit)
- vCPU 2, RAM 4 GB, **disk 40 GB (thin)** — Prometheus' TSDB grows with retention; give it
  headroom over the 25 GB used on app-01.
- Single NIC on port group **`sk07-dmz`**
- Boot the installer ISO.

Read the NIC MAC (ESXi VM settings → Network adapter → Advanced → MAC, or `ip link show
ens160` after first boot). It keys the DHCP static reservation in §2, and its DUID-LL form
`00:03:00:01:` + MAC keys the DHCPv6 one. **If you rebuild this VM the MAC changes** — read
the new one and replace every occurrence. A wrong MAC silently breaks all addressing: the
DMZ has no dynamic pool to fall back on, so a non-matching reservation yields no lease at
all (not even IPv6).

## 2. Networking (Ubuntu installer / netplan)

This host is **dual-stack** — Grafana must be reachable over IPv6 (S5.4 follows the same
IPv6 rule as the API, S3.9), so leave both stacks enabled. Accept DHCP for IPv4 and IPv6 in
the installer; the resulting `/etc/netplan/50-cloud-init.yaml` should be:

```yaml
network:
  version: 2
  ethernets:
    ens160:
      dhcp4: true
      dhcp6: true
      accept-ra: true
      nameservers:
        search: [kyber.local]
```

```
sudo tee /etc/systemd/networkd.conf >/dev/null <<'CONF'
[DHCPv6]
DUIDType=link-layer
CONF
sudo systemctl restart systemd-networkd
sudo netplan apply
ip -br addr show ens160     # expect 192.168.7.20 + 2001:1470:fffd:99::20
```

- Hostname (installer "Your server's name"): `kyber-mon`

## 3. First boot

```
sudo apt update && sudo apt -y full-upgrade
sudo reboot
```

## 4. Timezone + NTP (point at the VyOS relay)

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
ping -c2 1.1.1.1                           # WAN (apt + Grafana repo reachability)
ping6 -c2 2001:1470:fffd:99::1             # IPv6 gateway
getent ahosts kyber-ldap.kyber.local       # both families -> 192.168.7.30 + 2001:1470:fffd:99::30
getent ahosts kyber-app-01.kyber.local     # both families -> 192.168.7.10 + 2001:1470:fffd:99::10 (a scrape target)
```