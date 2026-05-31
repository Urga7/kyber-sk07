# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**kyber (sk07)** is an infrastructure-as-documentation project for a university networking/services lab. The team (group 7) builds and documents a multi-segment dual-stack (IPv4 + IPv6) network on ESXi: a VyOS router, DMZ servers, internal workstations, monitoring, and a REST API — all from scratch.

The repo is **mostly runbooks, not code**. Each segment/host has a numbered series of markdown runbooks containing the exact commands to paste into that box, in order. The one real codebase is the FastAPI REST API under `dmz-app-01/app/` (see below). `kyber-project-plan.md` is the single source of truth for all tasks, IP plans, VM inventory, and acceptance criteria — referenced throughout the runbooks by ID (e.g. `S3.6`, `N4.3`, `S9.1`). Read it before any work.

## Repository Layout

- `kyber-project-plan.md` — canonical task list, IP plan, VM inventory, acceptance criteria.
- `vyos/` — numbered VyOS runbooks `01-`–`07-` of raw `set`/`delete` commands: base addressing, IPv6-only segment, NTP/DNS, NAT, DHCP, SNMP (`06-enable-snmp.md`), zone firewall (`07-firewall-setup.md`). `vyos/snapshot-config.boot` is the live point-in-time `config.boot` export — kept in sync after every router change; it currently reflects a **fully-built router** (see Build status).
- `network/firewall-policy.md` — the human-readable, dual-stack, zone-based firewall policy with per-rule rationale; `vyos/07-firewall-setup.md` is its encoding (N6).
- `dmz-app-01/` — **Track S** app VM `kyber-app-01` (`192.168.7.10`): PostgreSQL primary + the FastAPI REST API. Runbooks `00–03`, plus the live app source in `app/`, `requirements.txt`, and `deploy.ps1`. See `dmz-app-01/README.md` for the data model, endpoint table, and HA plan.
- `dmz-ldap/` — **Track S** directory VM `kyber-ldap` (`192.168.7.30`): FreeIPA (LDAP + Kerberos + internal CA + integrated BIND DNS) install + post-install (users/groups/DNS records). `dmz-ldap/kyber-ipa-ca.crt` is the committed public CA cert clients trust.
- `dmz-mon/` — **Track S** monitoring VM `kyber-mon` (`192.168.7.20`): Prometheus + node/snmp exporters + Grafana (HTTPS via a FreeIPA cert). Runbooks `00–03`.
- `ws-01/` — internal **Ubuntu Desktop** client `kyber-ws-01` (heterogeneous-OS req S9.1); CA trust + REST acceptance suite.
- `ws-02/` — internal **Windows Server** (Desktop Experience) client `kyber-ws-02` (S9.2); same acceptance suite from Windows.
- `ipv6/` — host-side setup for the IPv6-only segment (`kyber-ipv6`, Netplan accept-RA/SLAAC).
- `kyber-network-topology.svg` — network diagram.

> The plan still references `/services/...` and `/network/README.md` doc paths; the actual repo uses **per-host directories** (`dmz-app-01/`, `dmz-ldap/`, `dmz-mon/`) instead. Only `network/firewall-policy.md` matches a plan path. Don't create the `/services/` tree.

## Key Network Constants

| Segment | IPv4 | IPv6 | VyOS iface |
|---|---|---|---|
| WAN | `88.200.24.237/25` (GW `.129`) | `2001:1470:fffd:98::2/64` | eth0 |
| Internal | `10.7.0.1/24` | `2001:1470:fffd:9a::1/64` | eth1 |
| DMZ | `192.168.7.1/24` | `2001:1470:fffd:99::1/64` | eth2 |
| IPv6-only | — | ULA `fd07:1:1:1::1/64`, NPTv6 outer `2001:1470:fffd:9b::/64` | eth3 |

Domain: `kyber.local`. Internal DNS / FreeIPA: `192.168.7.30` / `2001:1470:fffd:99::30`. REST API (`api.kyber.local`): `192.168.7.10` / `2001:1470:fffd:99::10`. DMZ servers get fixed IPs via DHCP **static reservations** (MAC→IP, keyed by DUID-LL `00:03:00:01:`+MAC for v6); internal workstations take **dynamic** leases (`10.7.0.100`–`.200`, `9a::100`–`::1ff`) with no reservation.

**IPv6 autoconfig mapping (as built):** both dual-stack LAN segments use **DHCPv6-stateful** (DMZ = per-DUID reservations, internal = dynamic pool; RAs carry `managed-flag` + `no-autonomous-flag`). **SLAAC** is used only on the IPv6-only segment (eth3 advertises the ULA `fd07:1:1:1::/64` autonomously). This satisfies the brief's "≥1 SLAAC, ≥1 DHCPv6" — the plan suggested SLAAC-on-internal but explicitly allowed swapping. The router also runs an NTP relay and an SNMPv2c agent (community `kyber-ro`, source-restricted to `kyber-mon`).

## Access pattern (recurs everywhere)

The DMZ and internal segments are not reachable from the WAN directly. Every remote command into a host goes **through the VyOS router as an SSH jump-host**:

```
ssh -J vyos@88.200.24.237 <user>@<host-ip>
```

DMZ hosts have fixed IPs; for dynamic-lease workstations, find the current IP first (`ip -br addr` on the host, or `show dhcp server leases` on the router keyed on the client hostname).

## The REST API (`dmz-app-01/app/`) — the one piece of real code

FastAPI + SQLAlchemy + PostgreSQL. Modules: `main.py` (routes), `database.py` (engine/session), `models.py` (ORM), `schemas.py` (Pydantic I/O), `auth.py` (LDAP Basic-auth gate), `serialization.py` (Accept-header content negotiation → JSON/XML/HTML, S3.3).

Architecture worth knowing before editing:
- **No app build/lint/test tooling exists.** Verification is the manual acceptance suite in `dmz-app-01/03-rest-api.md` §6, re-run from `ws-01`/`ws-02`. There are no unit tests.
- **Deploy is the workflow, not local run.** Edit `app/` locally, then push:
  ```powershell
  .\dmz-app-01\deploy.ps1        # scp app/ via jump-host → /opt/kyber-api/app, chown kyberapi, restart kyber-api
  ```
  In production the app runs as systemd unit `kyber-api` (uvicorn on `127.0.0.1:8000`) behind **nginx**, which terminates TLS (`443 ssl http2`, IPv4+IPv6) with a **FreeIPA-CA-issued** cert auto-renewed by certmonger. Host/service config (env file, systemd unit, nginx) lives on the box and is documented as commands in `03-rest-api.md`, not in the repo.
- **Config is env-driven** (`/etc/kyber-api.env` on the box, secrets never committed): `KYBER_DATABASE_URL`, `KYBER_LDAP_URI`, `KYBER_WRITER_GROUP` (default `api-writers`), `KYBER_AUTH_ENABLED` (set `0` to bypass LDAP for local testing).
- **Auth model (S3.8):** `GET` is public; `POST`/`PUT`/`DELETE` require HTTP Basic creds that bind to FreeIPA over `ldaps://` (trusting the IPA CA via the system store) **and** belong to `api-writers`. Reference users: `carol` ∈ group (allowed), `dave` ∉ (403). No bind → 401.
- To run locally without the VM, you need a reachable PostgreSQL and either `KYBER_AUTH_ENABLED=0` or LDAP reachability: `pip install -r requirements.txt` then `uvicorn app.main:app --reload` from `dmz-app-01/`.

## Conventions

- **Runbook numbering**: files in each host dir are numbered sequentially (`00-`, `01-`, …); new runbooks continue the sequence. Don't renumber existing files.
- **VyOS commands**: bare `set`/`delete` meant for configure mode (`configure` → commands → `commit` → `save` → `exit`). Note `show`/`generate` are **operational-mode** commands — from configure mode prefix them with `run`. After every meaningful router change, refresh the snapshot by running **`vyos/update-snapshot.sh`** (fetches `/config/config.boot` and **redacts secrets** — PKI private keys, password hashes — before writing `vyos/snapshot-config.boot`). **Never commit a raw `config.boot`**; it holds cleartext private keys.
- **Dual-stack always**: every host and service must be reachable over **both IPv4 and IPv6** — no host opts out of v6, including the FreeIPA box (`kyber-ldap` installs v4-first, then enables DHCPv6 → `::30`). The IPv6-only segment is v6-only by definition. Firewall rules must cover both stacks.
- **VM naming**: singleton VMs are unnumbered — `kyber-rtr`, `kyber-mon`, `kyber-ldap`, `kyber-ipv6`; only the paired VMs carry numbers — `kyber-app-01`/`-02`, `kyber-ws-01`/`-02`. Never `mon-01`/`rtr-01`.
- **Acceptance criteria IDs**: cite the relevant `S#`/`N#`/`I#` from `kyber-project-plan.md` in runbooks and commit messages.
- **OS-specific runbooks**: the same step is written for the target OS — `apt`/`ufw`/`systemd` + `curl` on Ubuntu hosts; `dnf`/`firewalld` on the RHEL-family FreeIPA box; PowerShell + `curl.exe` (not the `curl`→`Invoke-WebRequest` alias) on Windows. Mind PowerShell native-arg quirks: pass JSON bodies via `-d "@file.json"`, not inline `-d $body` (Windows PowerShell strips the inner quotes).

## Technology Stack (decided)

- Router/firewall: VyOS 1.4.4
- REST framework: Python / FastAPI (SQLAlchemy, uvicorn, nginx)
- Database: PostgreSQL
- User directory: FreeIPA (provides the CA for all internal TLS)
- VPN: OpenVPN on VyOS (FreeIPA/LDAP username+password auth via `openvpn-auth-ldap`)
- Monitoring: Prometheus + Grafana
- Consensus: etcd (RAFT, 3 nodes)

## Two-Track Workflow

Two parallel workstreams after bootstrap:
- **Track N (Networking)** — NAT, NPTv6, DHCP/DHCPv6/SLAAC, DNS forwarding, NTP, firewall, VPN, SNMP — all on `kyber-rtr` (`vyos/`).
- **Track S (Services)** — FreeIPA/LDAP, internal DNS, REST API, etcd, monitoring — on the DMZ app VMs (`dmz-app-01/`, `dmz-ldap/`) and exercised from the internal clients (`ws-01/`, `ws-02/`).

When generating config or docs, identify the track and follow the conventions of the existing files in that track's directory.

## Build status (2026-05-31 checkpoint)

Derived from the runbooks + the live `vyos/snapshot-config.boot`.

**Live / done:**
- **Track N** (`kyber-rtr`, all present in the snapshot): dual-stack addressing + static routes; NAT44 masquerade (N1); NPTv6 for the v6-only segment (N2); DHCPv4/DHCPv6 + RAs (N3); DNS forwarding incl. `kyber.local`→FreeIPA + the `7.168.192.in-addr.arpa` reverse zone (N4); NTP relay (N5); **zone-based dual-stack firewall (N6) — live**; SNMP agent `kyber-ro` restricted to `kyber-mon` (N8).
- **Track S:** FreeIPA on `kyber-ldap` — LDAP/Kerberos/CA + integrated DNS, users `alice/bob/carol/dave`, groups `vpn-users`/`api-writers` (S1, S2); PostgreSQL + FastAPI REST API on `kyber-app-01` with a FreeIPA TLS cert, content negotiation, LDAP-gated writes, IPv6 (S3.1–3.6, 3.8, 3.9); Prometheus + node/snmp exporters + Grafana on `kyber-mon` (S5); Ubuntu + Windows internal clients (S9); IPv6-only host via SLAAC (S8).

**Pending / not built:**
- **N7 VPN (WireGuard)** — not deployed. A **temporary `WAN→LOCAL` SSH accept (rule 40)** is live in the firewall to avoid lockout and **must be deleted once N7 is up** (router SSH then becomes VPN-only). The `udp/51820` WireGuard accept is already pre-staged.
- **S3.7 HA** — designed in `dmz-app-01/04-app-02-and-ha.md` (keepalived VIP `192.168.7.100`/`::100` + nginx **active-active** across app-01/app-02, **shared single PostgreSQL primary** on app-01 — accepted SPOF). **app-02 not built yet**; until executed, `api.kyber.local` still resolves to app-01 `.10`/`::10`.
- **S4 etcd** (3-node RAFT) — not started.
- **I1 DNAT** (`88.200.24.237:443` → VIP) — pending HA; the `WAN→DMZ tcp/443` accept is already in the firewall.
- Optional: NetFlow/ntopng (N9/S6), Suricata IDS (S7), HTTP/3 (S3.10), GraphQL (S3.11).

> **Every host is dual-stack — including the FreeIPA box.** `kyber-ldap` installs v4-first (clean FreeIPA install), then enables stateful DHCPv6 → `2001:1470:fffd:99::30` per `dmz-ldap/03-dhcpv6-prep.md` §2 (DUID-LL pinned via NetworkManager `ipv6.dhcp-duid`). If a v6 path to the directory misbehaves, confirm the address is up: `ip -6 addr show ens160` on `kyber-ldap` should show `::30`.