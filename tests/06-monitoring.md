# Test 06 — Monitoring: Prometheus + exporters + Grafana, ntopng NetFlow (S5, S6)

Validates the monitoring stack on `kyber-mon`: Prometheus scraping `snmp_exporter` (VyOS) and
`node_exporter` (Linux hosts), Grafana over HTTPS, and ntopng rendering the router's NetFlow v9
export. Mirrors `dmz-mon/02-prometheus-and-exporters.md` §5, `03-grafana.md` §7,
`04-ntopng-netflow.md` §7.

**Where to run:** `kyber-mon` for the backend checks; a CA-trusting client (`ws-01`/`ws-02`) for
the dashboards. Covers **S5.1–S5.5, S6.1–S6.2** (and the N8/N9 consumer side).

---

## 1. Prometheus targets all UP (S5.1, S5.2)

**Run on:** `kyber-mon`

```
curl -s 'http://127.0.0.1:9090/api/v1/targets' | grep -o '"health":"[a-z]*"' | sort | uniq -c
```

**Expect:** every target `"health":"up"` — the `node` jobs (`kyber-mon`, `kyber-app-01`,
`kyber-ldap`, and `kyber-app-02` if added) and the `snmp-vyos` job. A `down` node means
node_exporter isn't running there or its `*.kyber.local` name doesn't resolve from mon.

## 2. SNMP metrics flowing from the router (N8 → S5)

**Run on:** `kyber-mon`

```
# direct exporter proxy walk of the router (source = mon, which the kyber-ro ACL allows)
curl -s 'http://127.0.0.1:9116/snmp?target=192.168.7.1&module=if_mib&auth=kyber_v2' | grep -m3 ifHCInOctets
# the same counters in Prometheus
curl -s 'http://127.0.0.1:9090/api/v1/query?query=ifHCInOctets' | head -c 400
```

**Expect:** non-empty `ifHCInOctets` samples for the router's interfaces (the WAN/throughput data
the Grafana panels graph).

## 3. Short polling interval (brief: "sensibly short intervals")

**Run on:** `kyber-mon`

```
grep -E 'scrape_interval|evaluation_interval' /etc/prometheus/prometheus.yml
```

**Expect:** `scrape_interval: 15s` (≤30s as the brief asks).

## 4. Grafana over HTTPS with a real cert + live data (S5.3, S5.4, S5.5)

**Run on:** `kyber-mon`

```
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3000/login     # 200 (Grafana on loopback)
sudo ipa-getcert list | grep -E 'status|certificate:'                    # status: MONITORING
```

**Run on:** a CA-trusting client (`ws-01`/`ws-02`)

```
curl -s -o /dev/null -w '%{http_code}\n' https://grafana.kyber.local/login    # 200, no TLS warning
```

**Expect:** Grafana serves over `https://grafana.kyber.local` with a valid FreeIPA cert (dual-stack
`.20`/`::20`). Logged in, the dashboards (Node Exporter Full #1860 + the VyOS interface board) show
**live** data. Generate load and watch it move:

```
# on kyber-app-01 (or any DMZ host):
sudo apt update                          # pulls bytes through eth0 -> WAN-throughput panel rises
yes >/dev/null & sleep 20; kill %1       # CPU spike visible on that node's dashboard
```

## 5. ntopng — NetFlow top-talkers (S6.1, S6.2)

**Run on:** `kyber-mon`

```
sudo systemctl status kyber-nprobe --no-pager     # active (running) — the NetFlow v9 collector
sudo ss -lnup | grep ':2055'                       # nprobe listening on UDP/2055
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3001/    # 302 (ntopng up, behind nginx)
```

Drive **router-crossing** traffic (NOT same-subnet — that never reaches the router), then look in
the UI:

```
# on kyber-mon: outbound download crosses eth2->eth0
curl -o /dev/null https://speed.hetzner.de/100MB.bin
curl -6 -o /dev/null https://speed.hetzner.de/100MB.bin     # IPv6 flow -> dual-stack
```

**Run on:** a CA-trusting client

```
curl -s -o /dev/null -w '%{http_code}\n' https://ntopng.kyber.local/    # 200/302, valid cert
```

**Expect:** in ntopng **Hosts → Top Hosts** / **Flows**, the downloads appear with per-flow byte
counts; the `curl -6` shows as an IPv6 flow (dual-stack, S6.2). ntopng publishes at
`https://ntopng.kyber.local` with a valid FreeIPA cert. Screenshot top-talkers for the report.

> **Scope note:** Grafana and ntopng are **internal/VPN-only** — they are not part of the WAN
> publish. From outside they must be unreachable (the SNI-edge `geo` gate over IPv4; verify the
> IPv6 `WAN-DMZ6` state per [`02-firewall.md`](02-firewall.md) §4).
