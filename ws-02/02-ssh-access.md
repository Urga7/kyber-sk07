# kyber-ws-02 — SSH access (Windows Server)

Remote shell into the internal Windows workstation via the built-in OpenSSH server.
Prereq: OS + networking from [`00-os-install-config.md`](00-os-install-config.md)
is done and the box holds a dynamic internal lease (`10.7.0.100`–`.200`).

## 1. Install + enable the SSH server

In an **elevated** PowerShell on `kyber-ws-02`:

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Set-Service -Name sshd -StartupType Automatic
Start-Service sshd
```

The capability install also creates the inbound firewall rule
`OpenSSH-Server-In-TCP` (port 22). Confirm it is present and enabled:

```powershell
Get-NetFirewallRule -Name OpenSSH-Server-In-TCP | Select-Object Enabled,Direction,Action
# if missing:
# New-NetFirewallRule -Name sshd -DisplayName 'OpenSSH Server (sshd)' -Enabled True `
#   -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22
```

Optionally make PowerShell (not `cmd.exe`) the default shell for SSH sessions:

```powershell
New-ItemProperty -Path 'HKLM:\SOFTWARE\OpenSSH' -Name DefaultShell `
  -Value 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -PropertyType String -Force
```

## 2. Find the workstation's lease

Workstations take a **dynamic** lease, so the address is not fixed. Read it from
the host:

```powershell
Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias Ethernet0 |
  Select-Object IPAddress    # -> 10.7.0.x
```

or from the router (it lists the client hostname against each lease):

```
# on kyber-rtr-01, operational mode
show dhcp server leases
# look for the row whose hostname is kyber-ws-02 -> its IPv4
```

## 3. Install your public key (from the local machine)

The Administrator account is special on Windows OpenSSH: its keys live in
`C:\ProgramData\ssh\administrators_authorized_keys`, which must be owned by and
only writable to `Administrators`/`SYSTEM`. Push the key through the router jump
host, then fix the ACL:

```powershell
$ws  = '10.7.0.x'                                   # ws-02's current lease
$key = Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub
$aak = 'C:\ProgramData\ssh\administrators_authorized_keys'
$cmd = "powershell -NoProfile -Command \`"Add-Content -Path '$aak' -Value '$key'; icacls '$aak' /inheritance:r /grant 'Administrators:F' /grant 'SYSTEM:F'\`""
ssh -J vyos@88.200.24.237 Administrator@$ws $cmd
```

> For a **non-admin** local user instead, the standard path applies —
> `C:\Users\<user>\.ssh\authorized_keys` — and no special ACL is required.

## 4. Test

```powershell
ssh -J vyos@88.200.24.237 Administrator@10.7.0.x
```

> Because the lease is dynamic, the address can change across reboots. If a
> connection fails, re-check the lease (§2) before assuming a config problem.