# kyber-ws-02 — OS install & network (Windows Server, internal client)

VM: `kyber-ws-02` — internal segment (`sk07-internal` port group). Windows end-user client
satisfying the heterogeneous-OS requirement (S9.2), and the box used to run the REST API
acceptance suite in [`dmz-app-01/03-rest-api.md`](../dmz-app-01/03-rest-api.md) §6 from
Windows. CA trust + the tests are in [`01-ca-trust-and-acceptance.md`](01-ca-trust-and-acceptance.md).

OS: Windows Server (current release). Install the **Desktop Experience** edition so a GUI
and a browser are available for testing.

## 1. ESXi VM creation

- Guest OS: Windows → Microsoft Windows Server (64-bit)
- vCPU 2, RAM 4 GB, disk 40 GB (thin), single NIC on port group **`sk07-internal`**
- Install Windows Server (Desktop Experience), set an Administrator password.
- Rename and reboot (elevated PowerShell):

```powershell
Rename-Computer -NewName kyber-ws-02 -Restart
```

## 2. Networking (DHCP, dual-stack)

Leave the NIC on **DHCP** (the Windows default) — workstations are clients and take a dynamic
lease from the VyOS pools, with no static reservation. Windows does DHCPv4 and DHCPv6 out of
the box:

```powershell
ipconfig /all
# IPv4 10.7.0.x, Default Gateway 10.7.0.1, DNS 10.7.0.1, Connection-specific suffix kyber.local
# IPv6 2001:1470:fffd:9a::x (from the internal DHCPv6 pool)
```

The DHCP-supplied connection-specific suffix `kyber.local` lets short names resolve; FQDNs
like `api.kyber.local` go to `10.7.0.1` (VyOS), which forwards `kyber.local` to FreeIPA.
Confirm the split-DNS result (private DMZ address, N4.3):

```powershell
Resolve-DnsName api.kyber.local     # A 192.168.7.10 + AAAA 2001:1470:fffd:99::10
```

> Windows has no `systemd-resolved` `.local` quirk — its resolver sends `api.kyber.local`
> straight to the configured unicast DNS, so no search-domain workaround is needed here
> (contrast `ws-01`).

## 3. Sanity

```powershell
ping 10.7.0.1                       # internal gateway
ping 1.1.1.1                        # WAN (internet reachability, S9.3)
ping -6 2001:1470:fffd:9a::1        # IPv6 gateway
```

Continue with [`01-ca-trust-and-acceptance.md`](01-ca-trust-and-acceptance.md).
