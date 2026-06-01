# NetFlow analysis — ntopng on mon (S6)

Receives the NetFlow v9 export from the router (`vyos/09-netflow-export.md`, N9) and
renders top-talkers / flow analysis (S6.2). Published at **`https://ntopng.kyber.local`**
behind nginx with a FreeIPA cert — the same pattern as Grafana
([`03-grafana.md`](03-grafana.md)).

> **Why a collector process is needed.** ntopng is a flow **analyzer**, not a NetFlow
> **collector** — it cannot receive NetFlow UDP directly. A collector
> (**nProbe**) listens on UDP/2055, parses the v9 records, and hands them to ntopng over a
> local **ZMQ** socket. So the pipeline is:
>
> ```
> kyber-rtr  --NetFlow v9/UDP 2055-->  nProbe  --ZMQ tcp://127.0.0.1:5556-->  ntopng :3001
> ```
>
> nProbe is ntop's tool but is **licensed** — unlicensed it runs in **demo mode** (flow cap +
> periodic restart), which is fine for a lab screenshot. If the demo limits annoy you, the
> open-source **`netflow2ng`** is a drop-in replacement for nProbe (same ZMQ endpoint to
> ntopng) — noted in §5.

## 1. Install ntopng + nProbe (ntop APT repo)

Ubuntu's universe `ntopng` is old and has no ZMQ collector support; use the vendor repo. It
also pulls **redis**, which ntopng requires.

```
sudo apt -y install wget lsb-release
wget -q "https://packages.ntop.org/apt-stable/$(lsb_release -rs)/all/apt-ntop-stable.deb"
sudo apt -y install ./apt-ntop-stable.deb
sudo apt update
sudo apt -y install nprobe ntopng
```

The package ships `ntopng.service` (reads `/etc/ntopng/ntopng.conf`) and `redis-server`.
We add a small unit for nProbe ourselves so the collector is explicit and version-stable
(same hand-rolled-unit approach as node_exporter in [`02-…`](02-prometheus-and-exporters.md) §3).

## 2. nProbe — collect NetFlow on 2055, forward over ZMQ

Verify the pipeline by hand first (foreground), then make it persistent.

```
# foreground smoke-test (Ctrl-C to stop)
sudo nprobe -i none -n none --collector-port 2055 --ntopng "zmq://127.0.0.1:5556"
```

- `-i none` — don't capture from a local NIC (we're a *collector*, not a probe).
- `-n none` — don't re-export upstream; terminate flows here.
- `--collector-port 2055` — listen for the router's NetFlow v9.
- `--ntopng "zmq://127.0.0.1:5556"` — publish parsed flows for ntopng. (nProbe v11
  renamed this from the older `--zmq "tcp://…"`, which still works but prints a
  deprecation warning.)

Success looks like `Collecting flows from 192.168.7.1` plus a few
`Added new flow template definition […flow_version=9…]` lines — that's the router's v9
export being parsed.

With it running, drive a little traffic from a LAN host (`curl`, `apt update`); nProbe
should log received flows. Then install the unit:

```
sudo tee /etc/systemd/system/kyber-nprobe.service >/dev/null <<'UNIT'
[Unit]
Description=nProbe NetFlow collector -> ntopng (ZMQ)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/nprobe -i none -n none --collector-port 2055 --ntopng "zmq://127.0.0.1:5556"
Restart=always
RestartSec=5
# Demo mode stops EXPORTING after 300 s but the process stays alive, so Restart=always
# never fires on its own. Force a clean restart just under the cap to keep flows moving.
RuntimeMaxSec=240

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now kyber-nprobe
sudo ss -lnup | grep ':2055'      # expect nprobe listening on UDP/2055
```

> **nProbe demo-mode cap.** Unlicensed nProbe **stops exporting after 300 s** (or 5000 live
> flows, whichever first) — but it **keeps running**, so `Restart=always` alone won't cycle
> it. `RuntimeMaxSec=240` makes systemd terminate it every 4 min and `Restart=always` brings
> it straight back with a fresh budget, costing a ~5 s gap per cycle (ntopng re-registers the
> templates automatically). That's enough for live top-talker screenshots. A licence — or the
> free **`netflow2ng`** (§5) — removes the cap entirely.

## 3. ntopng — consume the ZMQ feed, web on loopback:3001

**Precondition:** the §2 collector must be running, or ntopng connects to nothing. The
foreground nProbe you smoke-tested exits on Ctrl-C — make sure the *service* is up and
holding the ZMQ port:

```
sudo systemctl status kyber-nprobe --no-pager     # active (running)
sudo ss -lnt | grep 5556                           # nProbe listening on the ZMQ port
```

ntopng is configured by a file of CLI options, **one per line**, at
`/etc/ntopng/ntopng.conf`. Overwrite it with just the three we need:

```
sudo tee /etc/ntopng/ntopng.conf >/dev/null <<'CONF'
-i=tcp://127.0.0.1:5556
-w=127.0.0.1:3001
-d=/var/lib/ntopng
-m=10.7.0.0/24,192.168.7.0/24,2001:1470:fffd:9a::/64,2001:1470:fffd:99::/64,fd07:1:1:1::/64
CONF
```

- `-i=tcp://127.0.0.1:5556` — the "interface" ntopng monitors. Pointing it at the **ZMQ
  endpoint** (not a NIC name like `eth0`) is what puts ntopng in **flow-collection** mode —
  it pulls nProbe's parsed flows instead of sniffing packets.
- `-w=127.0.0.1:3001` — bind the web UI to loopback **:3001** (Grafana already owns 3000);
  nginx fronts it with TLS in §6.
- `-d=/var/lib/ntopng` — ntopng's data/work directory.
- `-m=…` — **local networks** (dual-stack). In flow-collection mode there's no NIC for
  ntopng to infer "local" subnets from, so without this every host is classed *remote* and
  the **Local Hosts** / top-talker views stay empty (ntopng warns: *"No local hosts
  detected … configure local networks (-m parameter)"*). Lists internal + DMZ (both stacks)
  + the IPv6-only ULA; add the VPN pools (`10.7.99.0/24,fd07:99::/64`) if wanted.

Start it and confirm it serves locally:

```
sudo systemctl enable --now ntopng
sudo systemctl status ntopng --no-pager
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3001/   # -> 302 (redirect to login) = up
```

Confirm it actually latched onto the feed (not just that the process is up):

```
sudo journalctl -u ntopng -n30 --no-pager | grep -iE 'zmq|collect|5556'
```

> ntopng requires **redis** (pulled in by the package in §1). If ntopng won't start, check
> `systemctl status redis-server` first.

## 4. Verify the end-to-end path (S6.2)

> **Test with traffic that actually crosses the router.** The router only accounts flows
> that traverse one of its interfaces. Two hosts on the **same subnet** (e.g. mon → app-01,
> both in the DMZ `192.168.7.0/24`) talk **directly over the vSwitch and never reach the
> router**, so that traffic never appears in NetFlow — don't bother testing with it. Use an
> **outbound internet download** (DMZ→WAN, eth2→eth0) or **cross-segment** traffic instead.

```
# reset the demo counter first, then you have <5 min before the cap (see §2)
sudo systemctl restart kyber-nprobe

# router-crossing traffic: pull bytes mon -> WAN (any large file works)
curl -o /dev/null https://speed.hetzner.de/100MB.bin
curl -6 -o /dev/null https://speed.hetzner.de/100MB.bin    # IPv6 flow -> proves dual-stack

# nProbe should still be exporting (not capped)
sudo journalctl -u kyber-nprobe -n5 --no-pager | grep -iE 'collect|export'
```

In the ntopng UI: **Hosts → Top Hosts** and **Flows** list the talkers with byte counts;
the `curl -6` download appears as an IPv6 flow (dual-stack, S6.2). Screenshot the
top-talkers view for the report (plan §4 item 14).

> **Peek before publishing (§6):** ntopng's UI is bound to `127.0.0.1:3001` (mon-local). To
> open it from your laptop without nginx yet, tunnel through the jump host:
> ```
> ssh -J vyos@88.200.24.237 -L 3001:127.0.0.1:3001 kyber@192.168.7.20
> ```
> then browse `http://localhost:3001` (login `admin`/`admin`).

> **Cross-segment alternative (more interesting view):** from `ws-01` (internal) hit a DMZ
> service — `curl -k https://api.kyber.local/customers` — that crosses eth1→eth2 and shows
> internal↔DMZ talkers, not just outbound.

## 5. (alternative) netflow2ng — no demo cap

If the nProbe demo cap ever gets in the way, the open-source **`netflow2ng`** removes it —
same NetFlow v9 in, same ZMQ feed out (defaults already match: UDP/2055 in, ZMQ `:5556`
out), so **ntopng (§3) stays unchanged**; disable the nProbe unit and drop netflow2ng into
an analogous one. Cost: it ships no binary — a Go (≥1.23) build-from-source that links
`libzmq` via cgo, compiled on the host that runs it.

## 6. nginx + FreeIPA cert — publish ntopng.kyber.local

Identical pattern to Grafana ([`03-grafana.md`](03-grafana.md) §3–4); only the names/port
change. **On `kyber-ldap`:**

```
kinit admin
ipa dnsrecord-add kyber.local ntopng --a-rec=192.168.7.20 --aaaa-rec=2001:1470:fffd:99::20
ipa host-add ntopng.kyber.local --force
ipa service-add HTTP/ntopng.kyber.local
ipa service-add-host HTTP/ntopng.kyber.local --hosts=kyber-mon.kyber.local
```

**On `kyber-mon`:**

```
sudo ipa-getcert request \
  -K HTTP/ntopng.kyber.local \
  -N CN=ntopng.kyber.local \
  -D ntopng.kyber.local \
  -f /etc/ssl/kyber/ntopng.crt \
  -k /etc/ssl/kyber/ntopng.key \
  -C "systemctl reload nginx"

sudo tee /etc/nginx/sites-available/ntopng >/dev/null <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name ntopng.kyber.local;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ntopng.kyber.local;

    ssl_certificate     /etc/ssl/kyber/ntopng.crt;
    ssl_certificate_key /etc/ssl/kyber/ntopng.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # ntopng uses WebSockets for live flow updates
        proxy_set_header Upgrade           $http_upgrade;
        proxy_set_header Connection        "upgrade";
    }
}
NGINX
sudo ln -sf /etc/nginx/sites-available/ntopng /etc/nginx/sites-enabled/ntopng
sudo nginx -t && sudo systemctl reload nginx
```

> **Firewall.** Two distinct paths, both already covered by N6 — no new rules:
> - **router → mon, UDP/2055** (the export): `LOCAL → DMZ`, allowed by `LOCAL-OUT` (default
>   accept). Gate is only mon-local; mon runs no host firewall (VyOS zones do the filtering,
>   as with the exporters).
> - **internal users → ntopng, tcp/443**: the existing `INTERNAL → DMZ` 443 rule that already
>   publishes the API and Grafana. ntopng stays internal-only — **not** part of the WAN DNAT.

Log in at `https://ntopng.kyber.local` from a CA-trusting workstation
([`ws-01/`](../ws-01/), [`ws-02/`](../ws-02/)); default creds `admin`/`admin`, change on
first login.

## 7. Acceptance (S6.2)

- ntopng loads over HTTPS with a valid FreeIPA cert (no warning).
- With router-crossing traffic flowing (an outbound/cross-segment download, §4), **Hosts →
  Top Hosts** and **Flows** show the talkers and per-flow byte counts from the NetFlow export.
- Screenshot top-talkers + a flow table for report §14.
