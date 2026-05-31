# Prometheus + exporters (S5.1, S5.2, N8)

## 1. Install Prometheus + exporters (on mon)

The Debian/Ubuntu packages wire up users, systemd units, and config paths for you:

```
sudo apt -y install prometheus prometheus-node-exporter prometheus-snmp-exporter snmp
```

| Component | Service | Listens | Config |
|---|---|---|---|
| Prometheus | `prometheus` | `:9090` | `/etc/prometheus/prometheus.yml` |
| node_exporter | `prometheus-node-exporter` | `:9100` | `/etc/default/prometheus-node-exporter` |
| snmp_exporter | `prometheus-snmp-exporter` | `:9116` | `/etc/prometheus/snmp.yml` |

## 2. snmp_exporter — supply a real `snmp.yml`, then add the `kyber-ro` community

**The Debian/Ubuntu package ships no usable `snmp.yml`** — for licensing reasons it's a
comment-only stub with **no `modules:` section at all**, so any scrape fails with
`Unknown module 'if_mib'`. You must supply a config that defines `if_mib`. Quickest path is
the prebuilt config from the snmp_exporter release **matching the installed binary** (the
file format is version-specific, so the version must match):

```
prometheus-snmp-exporter --version              # note the X.Y.Z
VER=0.25.0                                       # <- set to that exact version
sudo curl -fSL -o /etc/prometheus/snmp.yml \
  "https://raw.githubusercontent.com/prometheus/snmp_exporter/v${VER}/snmp.yml"
grep -c '^  if_mib:' /etc/prometheus/snmp.yml    # -> 1
```

> No WAN egress from mon? Fetch that URL on a host that has it and copy it over via the
> jump-host — `scp -J vyos@88.200.24.237 snmp.yml kyber@192.168.7.20:/tmp/`, then
> `sudo install -m644 /tmp/snmp.yml /etc/prometheus/snmp.yml`. (Or generate one locally with
> `prometheus-snmp-generator` per `/usr/share/doc/prometheus-snmp-exporter/README.Debian`.)

The upstream file already has an `auths:` block. Add our read-only community as another
entry — **edit it in place; do not overwrite the file** (that wipes `modules:` and you're
back to `Unknown module`):

```
sudo sed -i '/^auths:/a\  kyber_v2:\n    community: kyber-ro\n    version: 2' /etc/prometheus/snmp.yml
sudo systemctl restart prometheus-snmp-exporter
sudo systemctl --no-pager status prometheus-snmp-exporter   # confirm it parsed + is running
```

Smoke-test — this proxies an SNMP walk of the router and returns Prometheus metrics.
**Run it from mon** (source `192.168.7.20`): the `kyber-ro` ACL is scoped to mon's IP, so a
self-query from the router (`192.168.7.1`) is denied by design and will only ever time out.

```
curl -s 'http://127.0.0.1:9116/snmp?target=192.168.7.1&module=if_mib&auth=kyber_v2' | grep -m3 ifHCInOctets
```

## 3. node_exporter on the other Linux VMs

S5.2 wants host metrics from the Linux servers, not just mon. node_exporter is already
running locally on mon (§1). Install it on **each** other Linux VM so mon can scrape `:9100`.
The steps differ by OS family.

**Ubuntu (`app-01`, later `app-02`)** — packaged; the service is `prometheus-node-exporter`:

```
sudo apt -y install prometheus-node-exporter
sudo systemctl enable --now prometheus-node-exporter
```

**AlmaLinux (`kyber-ldap`)** — not in the base repos, and EPEL 10 doesn't carry it, so use
the upstream static binary; the service is `node_exporter`. This box also runs **firewalld**
(FreeIPA), so `9100` must be opened to mon explicitly:

```
cd /tmp
VER=1.11.1          # pin a release; check https://github.com/prometheus/node_exporter/releases for newer
curl -fSLO https://github.com/prometheus/node_exporter/releases/download/v${VER}/node_exporter-${VER}.linux-amd64.tar.gz
tar xzf node_exporter-${VER}.linux-amd64.tar.gz
sudo install -o root -g root -m0755 node_exporter-${VER}.linux-amd64/node_exporter /usr/local/bin/node_exporter
sudo restorecon -v /usr/local/bin/node_exporter
sudo useradd --system --no-create-home --shell /sbin/nologin node_exporter
sudo tee /etc/systemd/system/node_exporter.service >/dev/null <<'UNIT'
[Unit]
Description=Prometheus Node Exporter
Wants=network-online.target
After=network-online.target

[Service]
User=node_exporter
Group=node_exporter
ExecStart=/usr/local/bin/node_exporter
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now node_exporter
# firewalld: allow only mon, both stacks
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="192.168.7.20/32" port port="9100" protocol="tcp" accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv6" source address="2001:1470:fffd:99::20/128" port port="9100" protocol="tcp" accept'
sudo firewall-cmd --reload
```

> node_exporter listens on `:9100` on all interfaces by default. The scrape is
> `mon → target:9100`, i.e. **DMZ → DMZ** (same zone) — allowed by the VyOS zone policy; on
> the AlmaLinux box **firewalld** is the gate, hence the rich-rules above. Keep `9100` on the
> private DMZ addresses; do **not** expose it toward WAN or INTERNAL.

## 4. Prometheus scrape config

Replace `/etc/prometheus/prometheus.yml` with:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets: ['localhost:9090']

  - job_name: node
    static_configs:
      - targets:
          - kyber-mon.kyber.local:9100
          - kyber-app-01.kyber.local:9100
          - kyber-ldap.kyber.local:9100
          # - kyber-app-02.kyber.local:9100   # add when app-02 is built

  # snmp_exporter is a proxy: Prometheus passes the device as ?target=, and the
  # request is sent to the exporter (127.0.0.1:9116), not to the device directly.
  - job_name: snmp-vyos
    metrics_path: /snmp
    params:
      module: [if_mib]
      auth: [kyber_v2]
    static_configs:
      - targets: ['192.168.7.1']      # kyber-rtr, DMZ-side address
    relabel_configs:
      - source_labels: [__address__]
        target_label: __param_target
      - source_labels: [__param_target]
        target_label: instance
      - target_label: __address__
        replacement: 127.0.0.1:9116    # the snmp_exporter itself
```

Apply:

```
sudo promtool check config /etc/prometheus/prometheus.yml    # validate before reloading
sudo systemctl reload prometheus
```

## 5. Verify

```
# SNMP path end-to-end, FROM mon (needs §1 + the N6 firewall rule). Numeric OID because
# Ubuntu ships net-snmp with MIBs disabled, so the name `ifDescr` errors ("Sub-id not found");
# `sudo apt install snmp-mibs-downloader` + uncomment `mibs :` in /etc/snmp/snmp.conf if you
# want symbolic names. ifDescr table = .1.3.6.1.2.1.2.2.1.2
snmpwalk -v2c -c kyber-ro 192.168.7.1 .1.3.6.1.2.1.2.2.1.2   # lists eth0..eth3 — N8.3 acceptance

# Prometheus sees every target as UP
curl -s 'http://127.0.0.1:9090/api/v1/targets' | grep -o '"health":"[a-z]*"' | sort | uniq -c

# a real sample is flowing for the WAN interface
curl -s 'http://127.0.0.1:9090/api/v1/query?query=ifHCInOctets' | head -c 400
```

All three `node` targets and the `snmp-vyos` target should report `"health":"up"`. If
`snmp-vyos` is down, re-check §1 (community/listen-address) and the N6 `udp/161` rule; if a
`node` target is down, confirm node_exporter is running on that host (§4) and DNS resolves
its `*.kyber.local` name from mon.