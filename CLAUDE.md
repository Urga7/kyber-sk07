# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**kyber (sk07)** is an infrastructure-as-documentation project for a university networking/services lab. The team (group 7) builds and documents a multi-segment dual-stack (IPv4 + IPv6) network on ESXi: a VyOS router, DMZ servers, internal workstations, monitoring, and a REST API — all from scratch.

The repo is **mostly runbooks, not code**. Each segment/host has a numbered series of markdown runbooks containing the exact commands to paste into that box, in order. The one real codebase is the FastAPI REST API under `dmz-app-01/app/` (see below). `kyber-project-plan.md` is the single source of truth for all tasks, IP plans, VM inventory, and acceptance criteria — referenced throughout the runbooks by ID (e.g. `S3.6`, `N4.3`, `S9.1`). Read it before any work.

## Repository Layout

- `kyber-project-plan.md` — canonical task list, IP plan, VM inventory, acceptance criteria.
- `vyos/` — numbered VyOS configuration runbooks (`01-…`, `02-…`) of raw `set`/`delete` commands. `vyos/snapshot-config.boot` is the current point-in-time `config.boot` export.
- `dmz-app-01/` — **Track S** app VM (`192.168.7.10`): PostgreSQL primary + the FastAPI REST API. Runbooks `00–03`, plus the live app source in `app/`, `requirements.txt`, and `deploy.ps1`. See `dmz-app-01/README.md` for the data model, endpoint table, and HA plan.
- `dmz-ldap/` — **Track S** directory VM (`192.168.7.30`): FreeIPA install + DHCPv6 prep. `kyber-ipa-ca.crt` is the committed public CA cert that clients trust.
- `ws-01/` — internal Ubuntu Desktop client (heterogeneous-OS req S9.1); CA trust + REST acceptance suite.
- `ws-02/` — internal Windows Server client (S9.2); same acceptance suite from Windows.
- `ipv6/` — host-side setup for the IPv6-only segment (e.g. Netplan/SLAAC).
- `kyber-network-topology.svg` — network diagram.

## Key Network Constants

| Segment | IPv4 | IPv6 | VyOS iface |
|---|---|---|---|
| WAN | `88.200.24.237/25` (GW `.129`) | `2001:1470:fffd:98::2/64` | eth0 |
| Internal | `10.7.0.1/24` | `2001:1470:fffd:9a::1/64` | eth1 |
| DMZ | `192.168.7.1/24` | `2001:1470:fffd:99::1/64` | eth2 |
| IPv6-only | — | ULA `fd07:1:1:1::1/64`, NPTv6 outer `2001:1470:fffd:9b::/64` | eth3 |

Domain: `kyber.local`. Internal DNS / FreeIPA: `192.168.7.30`. REST API (`api.kyber.local`): `192.168.7.10` / `2001:1470:fffd:99::10`. DMZ servers get fixed IPs via DHCP **static reservations** (MAC→IP); internal workstations take **dynamic** leases (`10.7.0.100`–`.200`, `9a::100`–`::1ff`) with no reservation.

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
- **VyOS commands**: bare `set`/`delete` meant for configure mode (`configure` → commands → `commit` → `save` → `exit`). Save a fresh `vyos/snapshot-config.boot` after every meaningful router change.
- **Dual-stack always**: every segment (except IPv6-only) has both IPv4 and IPv6; firewall rules must cover both stacks.
- **Acceptance criteria IDs**: cite the relevant `S#`/`N#`/`I#` from `kyber-project-plan.md` in runbooks and commit messages.
- **OS-specific runbooks**: the same step is written for the target OS — `apt`/`ufw`/`systemd` + `curl` on Ubuntu hosts; `dnf`/`firewalld` on the RHEL-family FreeIPA box; PowerShell + `curl.exe` (not the `curl`→`Invoke-WebRequest` alias) on Windows. Mind PowerShell native-arg quirks: pass JSON bodies via `-d "@file.json"`, not inline `-d $body` (Windows PowerShell strips the inner quotes).

## Technology Stack (decided)

- Router/firewall: VyOS 1.4.4
- REST framework: Python / FastAPI (SQLAlchemy, uvicorn, nginx)
- Database: PostgreSQL
- User directory: FreeIPA (provides the CA for all internal TLS)
- VPN: WireGuard
- Monitoring: Prometheus + Grafana
- Consensus: etcd (RAFT, 3 nodes)

## Two-Track Workflow

Two parallel workstreams after bootstrap:
- **Track N (Networking)** — NAT, NPTv6, DHCP/DHCPv6/SLAAC, DNS forwarding, NTP, firewall, VPN, SNMP — all on `kyber-rtr-01` (`vyos/`).
- **Track S (Services)** — FreeIPA/LDAP, internal DNS, REST API, etcd, monitoring — on the DMZ app VMs (`dmz-app-01/`, `dmz-ldap/`) and exercised from the internal clients (`ws-01/`, `ws-02/`).

When generating config or docs, identify the track and follow the conventions of the existing files in that track's directory.