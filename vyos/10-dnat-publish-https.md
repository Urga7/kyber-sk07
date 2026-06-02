# DNAT — publish HTTPS to the outside world (I1)

Destination NAT so that `88.200.24.237:443` from the WAN is forwarded to the dual-stack
HA VIP `192.168.7.100` in the DMZ. This is the **IPv4** half of external publishing; the
**SNI edge** that fans one `:443` out to `api` / `grafana` / `ntopng` lives on the app
nodes (`dmz-app-01/06-sni-edge-and-external-publish.md`). Together they complete plan item
**I1**.

> **Why DNAT only for IPv4.** We own a single public IPv4 (`88.200.24.237`) but the API
> lives on a *private* DMZ address (`192.168.7.100`), unreachable from the internet — so an
> inbound connection needs its destination rewritten to that private VIP. **IPv6 needs no
> NAT**: every DMZ host already has a globally-routable address, so the public `AAAA` points
> straight at it and the router just *routes* the packet. NAT here is purely an IPv4-scarcity
> workaround. See `network/firewall-policy.md` WAN→DMZ rules 20/21.

## 1. The DNAT rule

```
set nat destination rule 100 description 'DNAT WAN:443 -> HA VIP (I1)'
set nat destination rule 100 inbound-interface name 'eth0'
set nat destination rule 100 destination address '88.200.24.237'
set nat destination rule 100 destination port '443'
set nat destination rule 100 protocol 'tcp'
set nat destination rule 100 translation address '192.168.7.100'
```

- **Match** on inbound `eth0`, `tcp`, dst `88.200.24.237:443`. The port is only the
  *selector* — it decides which packets to grab.
- **Rewrite** the destination IP → `192.168.7.100`. The port is **not** translated (the
  edge nginx also listens on 443), so no `translation port` line.
- After the rewrite the packet's destination is a DMZ address, so it is reclassified from
  the `LOCAL` zone (the router's own `…237`) into the **WAN→DMZ** zone-pair — which is why
  the already-staged `WAN-DMZ` accept (next section) finally fires. Without this rule the
  packet stays `WAN→LOCAL` and is dropped.

> **No `udp/443` rule** unless you enable HTTP/3 (optional S3.10). QUIC would need a parallel
> `protocol udp` DNAT rule and the `WAN-DMZ` rule 21 (`udp/443`, already staged).

## 2. Firewall — already staged, no change

The forwarded packet now needs a `WAN → DMZ` accept on `tcp/443`. Both stacks already have it
(verify, don't re-add):

```
run show firewall ipv4 name WAN-DMZ        # rule 20: accept tcp/443
run show firewall ipv6 name WAN-DMZ6       # rule 20: accept tcp/443
```

- **IPv4:** `WAN-DMZ rule 20` accepts `tcp/443`. The DNAT above is what makes traffic *reach*
  this chain — firewall match happens **after** DNAT rewrites the destination.
- **IPv6:** `WAN-DMZ6 rule 20` currently accepts `tcp/443` to **any** DMZ host — too wide
  (it exposes Grafana/ntopng on `::20` to the WAN). Narrow it in §2b.

## 2b. Keep the dashboards off the public internet (IPv6)

Over IPv6 the dashboards go **direct** to mon `::20` (they bypass the app-node SNI edge), so
the only place to gate them is here at the perimeter. We want exactly one thing public on v6:
the **API VIP `::100`**. Narrow the blanket rule to that one destination — `grafana`/`ntopng`
on `::20` then fall through to `WAN-DMZ6`'s `default-action drop`:

```
set firewall ipv6 name WAN-DMZ6 rule 20 destination address '2001:1470:fffd:99::100'
```

That single line scopes the existing accept to the API VIP only. This affects **only the
`WAN → DMZ` path** — internal users (`INTERNAL-DMZ6`) and VPN users (`VPN-DMZ6`) reach the
dashboards through their own zone-pairs and are untouched.

> **To demo the dashboards from one external box** (instead of fully closed), add a
> source-scoped hole *before* the drop — e.g. your admin host's `/128`:
> ```
> set firewall ipv6 name WAN-DMZ6 rule 15 action accept
> set firewall ipv6 name WAN-DMZ6 rule 15 protocol tcp
> set firewall ipv6 name WAN-DMZ6 rule 15 destination address '2001:1470:fffd:99::20'
> set firewall ipv6 name WAN-DMZ6 rule 15 destination port '443'
> set firewall ipv6 name WAN-DMZ6 rule 15 source address '<your-admin-v6>/128'
> ```
> Mirror it with that box's IPv4 in the edge `geo $dash_ok` allow-list
> (`dmz-app-01/06-…` §3). Keep `network/firewall-policy.md` WAN→DMZ in sync with whichever
> stance you land on.

> **IPv4** needs no firewall change here — the dashboards are gated at the SNI edge
> (`dmz-app-01/06-…` §3 `geo $dash_ok`), because over v4 all `:443` lands on the one VIP and
> only the edge can tell the names apart.

## 3. Hairpin / reflexive NAT — not needed

Internal and VPN clients resolve `api.kyber.local` via **split DNS** (FreeIPA) to the
*private* VIP `192.168.7.100`, so their traffic never transits the public IP and needs no
hairpin/NAT-reflection rule. Only genuinely off-LAN clients hit `88.200.24.237` and use this
DNAT.

## 4. Commit, save, snapshot

```
commit
save
exit
```

Then refresh the redacted snapshot (per repo convention — never commit a raw `config.boot`):

```
vyos/update-snapshot.sh
```

## 5. Acceptance (I1, IPv4 path)

From a host that is **genuinely off-LAN and off-VPN** (phone hotspot, cloud VM):

```
# 1. raw reachability — was 'filtered/timeout' before this rule, now 'open'
nc -vz 88.200.24.237 443

# 2. full request without owning a domain: --resolve supplies the name->IP map locally,
#    so curl sends SNI=api.kyber.local and the edge (06-) routes it. --cacert trusts the IPA CA.
curl -v --resolve api.kyber.local:443:88.200.24.237 \
     --cacert dmz-ldap/kyber-ipa-ca.crt \
     https://api.kyber.local/health        # -> 200
```

On the router you can watch the translation:

```
run show nat destination statistics        # rule 100 packet/byte counters climbing
run show conntrack table ipv4 | grep 192.168.7.100
```

> The matching **IPv6** acceptance (direct, no NAT) and the **SNI fan-out** to grafana/ntopng
> are in `dmz-app-01/06-sni-edge-and-external-publish.md` §6.
