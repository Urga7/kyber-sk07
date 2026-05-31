# kyber-ws-01 — SSH access (Ubuntu)

Remote shell into the internal Ubuntu workstation. Prereq: OS + networking from
[`00-os-install-config.md`](00-os-install-config.md) is done and the box holds a
dynamic internal lease (`10.7.0.100`–`.200`).

## 1. Install + enable the SSH server

On `kyber-ws-01`:

```
sudo apt -y install openssh-server
sudo systemctl enable --now ssh
```

Ubuntu's `ufw` is inactive by default, so no host firewall change is needed.
The internal segment is not reachable from the WAN directly — access is via the
VyOS router as a jump host, governed by the Track N zone firewall.

## 2. Find the workstation's lease

Workstations take a **dynamic** lease, so the address is not fixed. Read it from
the host console:

```
ip -4 -br addr show ens160     # -> 10.7.0.x
```

or from the router (it lists the client hostname against each lease):

```
# on kyber-rtr, operational mode
show dhcp server leases
# look for the row whose hostname is kyber-ws-01 -> its IPv4
```

## 3. Install your public key (from the local machine)

Substitute the address found above. The key is pushed through the router jump host:

```powershell
$ws  = '10.7.0.101'                                   # ws-01's current lease
$cmd = 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | ssh -J vyos@88.200.24.237 kyber@$ws $cmd
```

(`kyber` = the local user created during install — adjust if you named it differently.)

## 4. Test

```
ssh -J vyos@88.200.24.237 kyber@10.7.0.101
```

> Because the lease is dynamic, the address can change across reboots. If a
> connection fails, re-check the lease (§2) before assuming a config problem. To
> pin it, add a DHCP static reservation on VyOS keyed on ws-01's MAC — but for a
> client box that's optional, unlike the mandatory DMZ-server reservations.