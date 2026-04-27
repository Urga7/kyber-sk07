# kyber (sk07) — Network Diagrams

## 1. Physical & logical topology

Shows VMs, port groups, IPv4 + IPv6 addressing, the IPv6 prefix layout
(`2001:1470:fffd:98::/62` split into four `/64`s), and the VPN overlay.

```mermaid
flowchart TB
    classDef router fill:#fff3cd,stroke:#856404,stroke-width:2px,color:#000
    classDef wanNode fill:#f8d7da,stroke:#721c24,color:#000
    classDef vmNode fill:#ffffff,stroke:#333,color:#000
    classDef vipNode fill:#ffeb99,stroke:#cc8400,stroke-dasharray:5 5,color:#000
    classDef vpnNode fill:#e7e0f5,stroke:#5a2a82,color:#000

    Internet((Internet))

    subgraph WAN["WAN  -  vSwitch0 / LRK-public-internet"]
        LRK["LRK ISP GW<br/>88.200.24.129<br/>2001:1470:fffd:98::1"]:::wanNode
    end

    Internet --- LRK
    LRK ====|"88.200.24.128/25<br/>2001:1470:fffd:98::/64<br/>(mandatory link /64)"| eth0

    subgraph RTR["kyber-rtr-01  -  VyOS 1.4.4<br/>(router - FW - NAT - NPTv6 - DHCP - DNS fwd - NTP - WireGuard)"]
        direction TB
        eth0["eth0 (WAN)<br/>88.200.24.237/25<br/>2001:1470:fffd:98::2/64"]:::router
        eth1["eth1 (internal)<br/>10.7.0.1/24<br/>2001:1470:fffd:99::1/64"]:::router
        eth2["eth2 (DMZ)<br/>192.168.7.1/24<br/>2001:1470:fffd:9a::1/64"]:::router
        eth3["eth3 (v6only)<br/>fd07:7::1/64<br/>NPTv6 -> 2001:1470:fffd:9b::/64"]:::router
    end

    eth1 ====|"sk07-internal<br/>DHCPv4 + SLAAC"| INT
    eth2 ====|"sk07-dmz<br/>DHCPv4 (reservations) + DHCPv6 stateful"| DMZ
    eth3 ====|"sk07-ipv6only<br/>SLAAC (ULA fd07:7::/64)"| V6

    subgraph INT["INTERNAL  -  10.7.0.0/24 + 2001:1470:fffd:99::/64"]
        wsLin["kyber-ws-lin<br/>Ubuntu Desktop<br/>(FreeIPA-joined)"]:::vmNode
        wsWin["kyber-ws-win<br/>Windows 10/11<br/>(directory-joined)"]:::vmNode
    end

    subgraph DMZ["DMZ  -  192.168.7.0/24 + 2001:1470:fffd:9a::/64"]
        vip{{"keepalived VIP<br/>192.168.7.100<br/>2001:1470:fffd:9a::100"}}:::vipNode
        app01["kyber-app-01<br/>.10 / ::9a::10<br/>nginx (TLS, h2)<br/>FastAPI - PG primary<br/>etcd-1 - BIND (split DNS)"]:::vmNode
        app02["kyber-app-02<br/>.11 / ::9a::11<br/>nginx (TLS, h2)<br/>FastAPI - PG replica<br/>etcd-2"]:::vmNode
        mon01["kyber-mon-01<br/>.20 / ::9a::20<br/>Prometheus - Grafana<br/>etcd-3 - (opt) Suricata"]:::vmNode
        ldap01["kyber-ldap-01<br/>.30 / ::9a::30<br/>FreeIPA<br/>(LDAP - KRB - CA)"]:::vmNode

        vip -.->|"failover"| app01
        vip -.->|"failover"| app02
        app01 <-->|"RAFT"| app02
        app02 <-->|"RAFT"| mon01
        mon01 <-->|"RAFT"| app01
    end

    subgraph V6["IPv6-ONLY  -  fd07:7::/64 (ULA, SLAAC)"]
        v6host["kyber-v6host<br/>Ubuntu - no IPv4 NIC"]:::vmNode
    end

    VPN[/"Remote VPN clients<br/>10.7.99.0/24<br/>fd07:7:99::/64"/]:::vpnNode
    VPN -.->|"WireGuard UDP/51820<br/>(over WAN)"| eth0
```

### Prefix accounting (for the report)

| /64 prefix                  | Where it lives           | Why                                    |
|-----------------------------|--------------------------|----------------------------------------|
| `2001:1470:fffd:98::/64`    | WAN link to LRK          | Required by ISP routing entry          |
| `2001:1470:fffd:99::/64`    | INTERNAL (eth1)          | SLAAC - autoconfig for end users       |
| `2001:1470:fffd:9a::/64`    | DMZ (eth2)               | DHCPv6 stateful - fixed server addrs   |
| `2001:1470:fffd:9b::/64`    | NPTv6 outer (egress only) | Maps to inner ULA `fd07:7::/64`       |

Total: 4 of 4 `/64`s in the assigned `/62` are accounted for.

---

## 2. External REST API request - sequence

A walkthrough of one external `GET /customers` (and one mutating `POST /orders`)
landing on the API. Useful for the "data flow" section of the report and for
explaining the auth + content-negotiation requirements at a glance.

```mermaid
sequenceDiagram
    autonumber
    actor U as External User
    participant PDNS as Public DNS
    participant RTR as VyOS<br/>(FW + DNAT)
    participant VIP as keepalived VIP<br/>(DMZ)
    participant NGX as nginx<br/>(TLS, HTTP/2)
    participant API as FastAPI
    participant LDAP as FreeIPA
    participant DB as PostgreSQL

    U->>PDNS: dig api.kyber.sk07.lab (A + AAAA)
    PDNS-->>U: 88.200.24.237 / 2001:1470:fffd:98::2

    U->>RTR: TLS ClientHello :443 (h2 ALPN)
    Note over RTR: FW: WAN->DMZ allow 443<br/>v4: DNAT to 192.168.7.100<br/>v6: routed direct to ::9a::100
    RTR->>VIP: forwarded
    VIP->>NGX: hits active master (app-01 or app-02)

    U->>NGX: HTTP/2 GET /customers<br/>Accept: application/xml
    NGX->>API: proxy_pass

    alt mutating request (POST/PUT/DELETE)
        U->>NGX: POST /orders + Bearer JWT
        NGX->>API: proxy_pass
        API->>LDAP: simple-bind uid=carol<br/>+ memberOf=api-writers?
        LDAP-->>API: bind OK + group OK
    end

    API->>DB: SELECT / INSERT
    DB-->>API: rows / id

    Note over API: Content negotiation on Accept:<br/>application/json | application/xml | text/html
    API-->>NGX: 200 (XML body)
    NGX-->>U: 200 over TLS h2
```

### What this diagram is evidence of (map to grading criteria)

| Requirement                            | Where it shows up in this flow                  |
|----------------------------------------|-------------------------------------------------|
| Split DNS                              | Step 1-2 (different answer than internal view)  |
| Firewall (WAN -> DMZ rules)            | Step 3 note                                     |
| TLS with real certs                    | Step 3 (ClientHello -> NGX)                     |
| HTTP/2                                 | Step 5 (h2 ALPN, h2 GET)                        |
| HA via keepalived                      | Step 4 (VIP)                                    |
| Content negotiation                    | Step 9 note                                     |
| LDAP-backed auth on protected ops      | "alt" branch                                    |
| Persistent DB                          | Step 7-8                                        |
| IPv6 reachability                      | Step 1-2 returning AAAA, step 3 v6 path note    |

---

## 3. Where to put these in the repo

```
/network/diagrams/
    README.md          <- this file
    topology.png       <- exported from section 1, for the PDF report
    topology@2x.png    <- 2x version for retina screens
    api-flow.png       <- exported from section 2
    api-flow@2x.png
```

Re-export every time the diagrams change. The Mermaid source above is the
authoritative version - the PNGs are derived artifacts.
