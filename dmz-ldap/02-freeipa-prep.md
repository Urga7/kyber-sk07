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

Point chrony at the VyOS NTP relay (configured in
`vyos/03-ntp-dns-hostname-setup.md`):

```
sudo apt -y install chrony
```

Deletes every line in the config starting with pool  or server . This strips the default Ubuntu public NTP pools (e.g. pool ntp.ubuntu.com) so
the VM won't reach out to internet time servers — the DMZ should get time only from the internal source.

```
sudo sed -i '/^pool /d;/^server /d' /etc/chrony/chrony.conf
```

```
echo "server 192.168.7.1 iburst" | sudo tee -a /etc/chrony/chrony.conf
sudo systemctl restart chrony
```
