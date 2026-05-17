# FreeIPA pre-install prep (LDAP VM)

Prepares `kyber-ldap` (192.168.7.30) so `ipa-server-install` will run cleanly.

```
sudo hostnamectl set-hostname kyber-ldap.kyber.local
```

## 1. Fix /etc/hosts

FreeIPA refuses to install if the FQDN resolves to a loopback address, so the
FQDN must map to the real IP and the Ubuntu-default `127.0.1.1` line must go.

```
sudo cp /etc/hosts /etc/hosts.bak
sudo sed -i '/^127\.0\.1\.1/d' /etc/hosts
grep -q '192.168.7.30' /etc/hosts || \
  echo '192.168.7.30    kyber-ldap.kyber.local kyber-ldap' | sudo tee -a /etc/hosts
```

`tee` echoes the appended line back to the terminal — that output is normal and
means the write succeeded.

Resulting `/etc/hosts` (the `127.0.0.1 localhost` line stays as-is; the IPv6
`::1 …` lines are harmless and can stay):

```
127.0.0.1       localhost
192.168.7.30    kyber-ldap.kyber.local kyber-ldap
```

Verify:

```
hostname -f                           # -> kyber-ldap.kyber.local
getent hosts kyber-ldap.kyber.local   # -> 192.168.7.30 (NOT 127.0.x.x)
```

## 2. Timezone + clock

Must match the rest of the network.

```
sudo timedatectl set-timezone Europe/Ljubljana
```

---
========== CHECKPOINT - Done up to here =====================
---

Point chrony at the VyOS NTP relay (configured in
`vyos/03-ntp-dns-hostname-setup.md`):

```
sudo apt -y install chrony
sudo sed -i '/^pool /d;/^server /d' /etc/chrony/chrony.conf
echo "server 192.168.7.1 iburst" | sudo tee -a /etc/chrony/chrony.conf
sudo systemctl restart chrony
chronyc sources    # 192.168.7.1 should appear as a candidate
```

> **Router prerequisite (run on `kyber-rtr-01`, not the VM).** VyOS's
> `ntp allow-client` in `vyos/03-ntp-dns-hostname-setup.md` only lists the IPv6
> DMZ/internal prefixes, so NTP from 192.168.7.30 is currently blocked. Add the
> IPv4 prefixes, then snapshot per the working agreement:
>
> ```
> configure
> set service ntp allow-client address '192.168.7.0/24'
> set service ntp allow-client address '10.7.0.0/24'
> commit ; save ; exit
> ```
>
> Fold this edit back into `vyos/03-ntp-dns-hostname-setup.md` and save a fresh
> `vyos/snapshots/config-YYYYMMDD-HHMM.boot`.

## 3. Sanity check

```
ping -c2 192.168.7.1                   # gateway
ping -c2 1.1.1.1                       # WAN
getent hosts kyber-ldap.kyber.local   # must return 192.168.7.30
```

When all three pass, proceed to FreeIPA install (next runbook:
`03-freeipa-install.md`, capturing the exact `ipa-server-install` command and
post-install steps from `ldap-setup.txt`).
