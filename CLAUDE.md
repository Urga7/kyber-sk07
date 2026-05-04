# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**kyber (sk07)** is an infrastructure-as-documentation project for a university networking/services lab. The team (group 7) builds and documents a multi-segment dual-stack (IPv4 + IPv6) network on ESXi, including a VyOS router, DMZ servers, monitoring, and a REST API — all from scratch.

There is no application source code to build, lint, or test. The repo contains:
- VyOS router configuration runbooks (step-by-step `set` commands)
- VyOS `config.boot` snapshots (point-in-time router state)
- Ubuntu host setup guides
- A network topology diagram (SVG)
- The canonical project plan with all tasks and acceptance criteria

## Repository Layout

- `kyber-project-plan.md` — Single source of truth for all tasks, IP plans, VM inventory, and acceptance criteria. Reference this before any work.
- `vyos/` — Numbered markdown runbooks (`01-…`, `02-…`, `03-…`) documenting VyOS configuration changes in order. Each file contains raw VyOS `set` commands to be pasted into configure mode.
- `vyos/snapshots/` — Timestamped `config.boot` exports (e.g. `config-20260429-1800.boot`). A new snapshot must be saved after every meaningful VyOS change.
- `ipv6/` — Host-side setup guides for Ubuntu VM in the IPv6 subnetwork (e.g. Netplan SLAAC config).
- `kyber-network-topology.svg` — Network diagram.

## Key Network Constants

| Segment | IPv4 | IPv6 | VyOS iface |
|---|---|---|---|
| WAN | `88.200.24.237/25` (GW `.129`) | `2001:1470:fffd:98::2/64` | eth0 |
| Internal | `10.7.0.1/24` | `2001:1470:fffd:9a::1/64` | eth1 |
| DMZ | `192.168.7.1/24` | `2001:1470:fffd:99::1/64` | eth2 |
| IPv6-only | — | ULA `fd07:1:1:1::1/64`, NPTv6 outer `2001:1470:fffd:9b::/64` | eth3 |

Domain: `kyber.local`. Internal DNS server: `192.168.7.10`.

## Conventions

- **Runbook numbering**: Files in `vyos/` and `ipv6-ubuntu/` are numbered sequentially (`00-`, `01-`, `02-`, …). New runbooks continue the sequence.
- **Snapshot naming**: `config-YYYYMMDD-HHMM.boot` in `vyos/snapshots/`.
- **VyOS commands**: Written as bare `set`/`delete` commands meant to be run inside VyOS configure mode (`configure` → commands → `commit` → `save` → `exit`).
- **Dual-stack always**: Every segment (except IPv6-only) must have both IPv4 and IPv6 configured. Firewall rules must cover both stacks.
- **DHCP for servers**: DMZ servers get fixed IPs via DHCP static reservations (MAC→IP), not manual static config on the host.

## Technology Stack (decided)

- Router/firewall: VyOS 1.4.4
- REST framework: Python / FastAPI
- Database: PostgreSQL
- User directory: FreeIPA
- VPN: WireGuard
- Monitoring: Prometheus + Grafana
- Consensus: etcd (RAFT, 3 nodes)

## Two-Track Workflow

The project has two parallel workstreams after bootstrap:
- **Track N (Networking)** — NAT, NPTv6, DHCP/DHCPv6/SLAAC, DNS forwarding, NTP, firewall, VPN, SNMP — all on `kyber-rtr-01`.
- **Track S (Services)** — LDAP, internal DNS (BIND9), REST API, etcd cluster, monitoring — on the DMZ application VMs.

When generating configuration or documentation, identify which track the work belongs to and follow the conventions of existing files in that track's directory.
