# NetFlow export to mon (N9)

Export per-flow accounting from the router to the collector on `kyber-mon`
(`192.168.7.20`, UDP/2055). This is the **export** half (N9); the **analysis** half
(nProbe + ntopng) lives in `dmz-mon/04-ntopng-netflow.md` (S6).

We use **NetFlow v9**, not sFlow: lab traffic is tiny, so sFlow's packet-sampling
(its only real advantage — offloading high-rate ASICs) buys nothing and would only make
us *miss* the sparse traffic we want to see. NetFlow v9 with `sampling-rate 1` accounts
**every** packet, giving exact top-talker tables — which is what S6.2 asks to screenshot.

**Dual-stack:** the version choice is what makes this v6-aware. NetFlow **v5 is IPv4-only**
and cannot carry an IPv6 address; **v9** templates include `IPV6_SRC/DST_ADDR`, so IPv6
conversations are accounted and surface in ntopng alongside v4. VyOS flow-accounting
(pmacct/uacctd) hooks each interface regardless of address family. The export **transport**
to the collector (`192.168.7.20:2055`) is IPv4 — that's only the delivery channel and
carries records about *both* families; it does not make the exporter blind to v6. (The
collector could be the v6 address instead, but it adds no coverage.)

## 1. Configure flow-accounting

```
set system flow-accounting netflow version '9'
set system flow-accounting netflow server 192.168.7.20 port '2055'
set system flow-accounting netflow source-address '192.168.7.1'
set system flow-accounting netflow sampling-rate '1'
set system flow-accounting interface 'eth0'
set system flow-accounting interface 'eth1'
set system flow-accounting interface 'eth2'
set system flow-accounting interface 'eth3'
```

> **Which interfaces.** `eth0` (WAN) is the headline view — everything NAT/NPTv6'd to the
> internet. But east-west traffic (internal↔DMZ, e.g. a workstation hitting the REST API)
> **never touches eth0**, so `eth1` (internal) and `eth2` (DMZ) are added for the full
> picture. A transiting packet is then accounted on both its ingress and egress interface;
> ntopng/nProbe dedups by flow, so this double-count is cosmetic, not a problem.
>
> **`eth3` (IPv6-only)** is included for complete per-segment, dual-stack coverage. Its
> internet traffic *also* appears on eth0 — but there it shows the **NPTv6 outer** source
> (`2001:1470:fffd:9b::…`); accounting eth3 additionally captures the **inner ULA**
> (`fd07:1:1:1::…`) pre-translation, so you can see the real v6-only talkers and watch NPTv6
> rewrite a flow (same conversation, two source prefixes on eth3 vs eth0). Drop eth3 only if
> you specifically want to avoid that NPTv6 double-view.
>
> `sampling-rate 1` = account every packet (correct for lab volumes). Raise it only if the
> in-memory flow table ever pressures the router — it won't here.

`commit` + `save`.

## 2. Firewall — nothing to add

The export is **router-originated** traffic (`LOCAL → DMZ`, UDP/2055 to mon). The `DMZ`
zone's `from LOCAL` chain is `LOCAL-OUT` / `LOCAL-OUT6`, both `default-action accept`, so
egress is **already permitted** — unlike SNMP (N8), NetFlow needs no new VyOS rule. The
only gate is on mon itself (nProbe must be listening on 2055, and mon's host firewall, if
any, must allow it) — handled in the mon runbook.

## 3. Verify (operational mode)

```
show flow-accounting interface eth0
```

> From configure mode prefix with `run` (`run show flow-accounting interface eth0`).

This prints the live flow table — src/dst, ports, packets, bytes. Seeing rows here proves
the router is accounting; whether they **reach** mon is verified on the collector side
(`dmz-mon/04-ntopng-netflow.md` §4). Generate a little traffic from a LAN host
(`curl`, `apt update`) and confirm flows appear.

## 4. Snapshot

Flow-accounting holds no secrets, so the redaction pass leaves it untouched — but the
snapshot must still reflect it:

```
./vyos/update-snapshot.sh
```

Commit citing **N9**.
