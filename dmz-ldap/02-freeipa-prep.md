# FreeIPA pre-install prep (LDAP VM — AlmaLinux 10)

Prepares `kyber-ldap` (192.168.7.30) so `ipa-server-install` will run cleanly.

```
sudo hostnamectl set-hostname kyber-ldap.kyber.local
```

## 1. Fix /etc/hosts

FreeIPA refuses to install if the FQDN resolves to a loopback address, so the
FQDN must map to the real IP. Rocky's installer normally does **not** add a
loopback hostname line (the `127.0.1.1` line is Ubuntu-specific), but the
removal below is kept defensively in case one exists.

```
sudo cp /etc/hosts /etc/hosts.bak
sudo sed -i '/^127\.0\.1\.1/d' /etc/hosts
grep -q '192.168.7.30' /etc/hosts || \
  echo '192.168.7.30    kyber-ldap.kyber.local kyber-ldap' | sudo tee -a /etc/hosts
```

`tee` echoes the appended line back to the terminal — that output is normal and
means the write succeeded.

```
sudo cp /etc/nsswitch.conf /etc/nsswitch.conf.bak
sudo sed -i -E '/^hosts:/ s/[[:space:]]+myhostname//' /etc/nsswitch.conf
```


Resulting `/etc/hosts` (the `127.0.0.1`/`::1 localhost` lines stay as-is):

```
127.0.0.1       localhost localhost.localdomain
::1             localhost localhost.localdomain
192.168.7.30    kyber-ldap.kyber.local kyber-ldap
```

Verify:

```
hostname -f                           # -> kyber-ldap.kyber.local
getent hosts kyber-ldap.kyber.local   # -> 192.168.7.30
```

## 2. Timezone + clock

```
sudo timedatectl set-timezone Europe/Ljubljana
```

AlmaLinux 10 ships chrony already installed and running (no package install,
and no `systemd-timesyncd` to remove — RHEL never used it). Note the RHEL-specific
paths: the config is `/etc/chrony.conf` (not `/etc/chrony/chrony.conf`) and the
service is `chronyd` (not `chrony`). Point it at the VyOS NTP relay (configured
in `vyos/03-ntp-dns-hostname-setup.md`).

The `sed` below deletes every line starting with `pool ` or `server `. This
strips AlmaLinux's default public NTP pool (`pool 2.almalinux.pool.ntp.org iburst`) so
the VM won't reach out to internet time servers — the DMZ should get time only
from the internal source.

```
sudo sed -i '/^pool /d;/^server /d' /etc/chrony.conf
echo "server 192.168.7.1 iburst" | sudo tee -a /etc/chrony.conf
sudo systemctl restart chronyd
chronyc sources    # 192.168.7.1 should appear as a candidate
```

> **Router prerequisite (run on `kyber-rtr-01`, not the VM).** VyOS's
> `ntp allow-client` in `vyos/03-ntp-dns-hostname-setup.md` only listed the IPv6
> DMZ/internal prefixes, so NTP from 192.168.7.30 is blocked until the IPv4
> prefixes are added:
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

## 3. SELinux + firewall (RHEL-specific — did not apply on Ubuntu)

Leave **SELinux enforcing**. FreeIPA is developed and tested against enforcing
mode — do not disable it.

```
getenforce                            # -> Enforcing
```

AlmaLinux runs **firewalld** by default, and unlike Ubuntu's (off-by-default) ufw it
*will* block FreeIPA and LDAP/Kerberos/DNS from other DMZ hosts. Open the
FreeIPA ports now (integrated-DNS deployment, so port 53 included):

```
sudo firewall-cmd --permanent --add-port={80,443,389,636,88,464,53}/tcp
sudo firewall-cmd --permanent --add-port={88,464,53}/udp
sudo firewall-cmd --reload
sudo firewall-cmd --list-ports
```

NTP (123/udp) is intentionally not opened: with `--no-ntp` this host is a chrony
*client* of VyOS, not a time server for the DMZ.

## 4. Sanity check

```
ping -c2 192.168.7.1                  # gateway
ping -c2 1.1.1.1                      # WAN
getent hosts kyber-ldap.kyber.local   # must return 192.168.7.30
```
