# Grafana — dashboards over HTTPS (S5.3, S5.4, S5.5)

Installs **Grafana** on `kyber-mon`, points it at the Prometheus from
[`02-prometheus-and-exporters.md`](02-prometheus-and-exporters.md), and publishes it at
**`https://grafana.kyber.local`** behind **nginx** with a **FreeIPA-issued certificate** —
the same real-cert + dual-stack pattern used for the REST API
([`dmz-app-01/03-rest-api.md`](../dmz-app-01/03-rest-api.md) §4–5).

## 1. Install Grafana (official APT repo)

Ubuntu's universe Grafana lags badly; use the vendor repo:

```
sudo apt -y install apt-transport-https software-properties-common wget
sudo mkdir -p /etc/apt/keyrings
wget -q -O - https://apt.grafana.com/gpg.key | gpg --dearmor | sudo tee /etc/apt/keyrings/grafana.gpg >/dev/null
echo "deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main" \
  | sudo tee /etc/apt/sources.list.d/grafana.list
sudo apt update && sudo apt -y install grafana
```

## 2. Bind Grafana to loopback

nginx is the only public listener (it terminates TLS), so Grafana itself listens on
`127.0.0.1:3000`. Edit `/etc/grafana/grafana.ini`:

```ini
[server]
protocol = http
http_addr = 127.0.0.1
http_port = 3000
domain = grafana.kyber.local
root_url = https://grafana.kyber.local/
enforce_domain = false
```

```
sudo systemctl enable --now grafana-server
sudo systemctl status grafana-server --no-pager
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3000/login   # 200 from Grafana directly
```

## 3. FreeIPA enrollment + TLS certificate (S5.4)

Real cert from the FreeIPA CA via `certmonger` (auto-renewing), exactly as app-01 did.
Enrolling the host also drops the IPA CA into mon's system trust store.

> **Prerequisites:** (1) DNS resolves `kyber.local` (the VyOS forwarder repoint — `00-…`), and
> (2) mon's hostname is a **FQDN**. `ipa-client-install` aborts with *"invalid hostname: not
> fully qualified"* if the system hostname is the short `kyber-mon` — set it like the
> `dmz-ldap` prep did.

```
# FreeIPA requires a fully-qualified system hostname
sudo hostnamectl set-hostname kyber-mon.kyber.local
hostname -f                                  # -> kyber-mon.kyber.local

sudo apt -y install freeipa-client
sudo ipa-client-install \
  --domain=kyber.local --realm=KYBER.LOCAL \
  --server=kyber-ldap.kyber.local \
  --mkhomedir --no-ntp
```

**On `kyber-ldap` (as an IPA admin)** — publish the `grafana` name and authorize mon to
get the cert. `grafana.kyber.local` is only a DNS alias for mon, so the host object must
be created with `--force` before a service can be added to it:

```
kinit admin
ipa dnsrecord-add kyber.local grafana --a-rec=192.168.7.20 --aaaa-rec=2001:1470:fffd:99::20
ipa host-add grafana.kyber.local --force
ipa service-add HTTP/grafana.kyber.local
ipa service-add-host HTTP/grafana.kyber.local --hosts=kyber-mon.kyber.local
```

**Back on `kyber-mon`** — request the cert; certmonger tracks/renews it and reloads
nginx on renewal. `-N CN=grafana.kyber.local` is required, or the IPA CA rejects the CSR
(its default CN would be `kyber-mon`, which doesn't match the `HTTP/grafana…` principal):

```
sudo mkdir -p /etc/ssl/kyber
sudo ipa-getcert request \
  -K HTTP/grafana.kyber.local \
  -N CN=grafana.kyber.local \
  -D grafana.kyber.local \
  -f /etc/ssl/kyber/grafana.crt \
  -k /etc/ssl/kyber/grafana.key \
  -C "systemctl reload nginx"
sudo ipa-getcert list      # status should reach MONITORING (= issued, tracking)
```

## 4. nginx — TLS, HTTP/2, dual-stack reverse proxy

```
sudo apt -y install nginx
sudo tee /etc/nginx/sites-available/grafana >/dev/null <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name grafana.kyber.local;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name grafana.kyber.local;

    ssl_certificate     /etc/ssl/kyber/grafana.crt;
    ssl_certificate_key /etc/ssl/kyber/grafana.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Grafana Live (dashboards, alerting) uses WebSockets
    location /api/live/ {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host       $host;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
NGINX

sudo ln -sf /etc/nginx/sites-available/grafana /etc/nginx/sites-enabled/grafana
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

The dual `listen`/`listen [::]` pair makes Grafana reachable at both `192.168.7.20` and
`2001:1470:fffd:99::20` (IPv6, like S3.9).

> **Firewall (coordinate with N6).** Internal users reaching Grafana is `INTERNAL → DMZ` on
> `tcp/443` — the same rule that already publishes the API. Confirm it covers mon's
> address on both stacks. Grafana stays internal-only; it is **not** part of the WAN DNAT.

## 5. Provision the Prometheus data source

Declarative provisioning beats clicking through the UI and is reproducible:

```
sudo tee /etc/grafana/provisioning/datasources/prometheus.yml >/dev/null <<'DS'
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://127.0.0.1:9090
    isDefault: true
DS
sudo systemctl restart grafana-server
```

## 6. Dashboards (S5.3)

Log in at `https://grafana.kyber.local` (default `admin`/`admin`, change on first login from
a host that trusts the IPA CA — the workstations in [`ws-01/`](../ws-01/) and
[`ws-02/`](../ws-02/)).

Import two community dashboards (Dashboards → New → Import → enter ID → select the
**Prometheus** data source):

| ID | Dashboard | Covers |
|---|---|---|
| `1860` | Node Exporter Full | CPU + memory + disk of every `node` target (S5.3 "CPU and memory of at least one server") |
| `1124` or similar SNMP/interface board | per-interface throughput | starting point for the VyOS traffic view |

For the required **WAN traffic over time / per-interface throughput** panel (S5.3), build a
time series with these PromQL queries (bits/s from the SNMP octet counters):

```promql
# WAN (eth0) inbound, bits per second
rate(ifHCInOctets{instance="192.168.7.1", ifName="eth0"}[1m]) * 8
# WAN (eth0) outbound
rate(ifHCOutOctets{instance="192.168.7.1", ifName="eth0"}[1m]) * 8
# all interfaces at once — legend {{ifName}}
rate(ifHCInOctets{instance="192.168.7.1"}[1m]) * 8
```

> The interface label key depends on the `if_mib` lookups in your `snmp.yml` — it is usually
> `ifName` (`eth0`…), sometimes `ifDescr`. If the queries return nothing, run
> `ifDescr` / `ifName` in Explore to see which label your exporter emits, and adjust.

## 7. Acceptance (S5.5)

- Dashboards load over `https://grafana.kyber.local` with a **valid** cert (no warning) from
  a CA-trusting client, and show **live** data.
- Generate load and watch it appear:
  ```
  # on app-01 or any DMZ host — drive WAN traffic and CPU
  sudo apt update           # pulls bytes through eth0 -> WAN throughput panel moves
  yes >/dev/null & sleep 20; kill %1   # CPU spike on that node in dashboard 1860
  ```
- `ipa-getcert list` stays at **MONITORING** (cert valid and auto-renewing).
