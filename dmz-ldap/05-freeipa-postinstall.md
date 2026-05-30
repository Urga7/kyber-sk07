# FreeIPA post-install — DNS records, groups, users, CA (LDAP VM — AlmaLinux 10)

## 1. Reverse zone + PTR (S2; `--no-reverse` was set at install)

The IPv4 DMZ reverse zone is referenced by the VyOS forwarder
(`0.7.10`/`7.168.192` in `vyos/03-…`), so create it explicitly:

```
ipa dnszone-add 7.168.192.in-addr.arpa. --name-from-ip=192.168.7.0/24
ipa dnsrecord-add 7.168.192.in-addr.arpa. 30 --ptr-rec=kyber-ldap.kyber.local.
ipa dnsrecord-add 7.168.192.in-addr.arpa 10 --ptr-rec=kyber-app-01.kyber.local.
ipa dnsrecord-add 7.168.192.in-addr.arpa 11 --ptr-rec=kyber-app-02.kyber.local.
ipa dnsrecord-add 7.168.192.in-addr.arpa 20 --ptr-rec=kyber-mon-01.kyber.local.
ipa dnszone-add 9.9.0.0.d.f.f.f.0.7.4.1.1.0.0.2.ip6.arpa
dig @127.0.0.1 -x 192.168.7.30 +short   # -> kyber-ldap.kyber.local.
```

## 2. Pre-register the other DMZ hosts

So `kyber.local` names resolve while those VMs are still being built (IPs from
plan N3.2). Only IPv4 is registered now — these hosts have no DHCPv6
reservation yet; add AAAA records when their v6 addresses are assigned (N3.4):

```
ipa dnsrecord-add kyber.local kyber-app-01 --a-rec=192.168.7.10
ipa dnsrecord-add kyber.local kyber-app-02 --a-rec=192.168.7.11
ipa dnsrecord-add kyber.local kyber-mon-01 --a-rec=192.168.7.20

ipa dnsrecord-add kyber.local kyber-app-01 --aaaa-rec=2001:1470:fffd:99::10
ipa dnsrecord-add kyber.local kyber-app-02 --aaaa-rec=2001:1470:fffd:99::11
ipa dnsrecord-add kyber.local kyber-mon-01 --aaaa-rec=2001:1470:fffd:99::20

```

## 3. Groups (S1.3)

FreeIPA ships `admins` and `ipausers` (the "users" group) already, so only the
two project-specific groups are created:

```
ipa group-add vpn-users  --desc="Allowed to connect via VPN"
ipa group-add api-writers --desc="Can call REST API write endpoints"
```

## 4. Test users (S1.4)

`--random` prints a one-time password per user; each must change it on first
`kinit`. Capture the printed passwords.

```
ipa user-add alice --first=Alice --last=Anderson --random
ipa user-add bob   --first=Bob   --last=Brown    --random
ipa user-add carol --first=Carol --last=Carter   --random
ipa user-add dave  --first=Dave  --last=Davis    --random
```

```
alice random password: 5On/?BbUK,)D(l;+dCk-Xc
bob random password: 3Ay.*_jjNk|%;.ZjcIOLou
carol random password: 1Xs~9dnhoJ(@K>BL;fjZ~Y
dave random password: 8Bd:.3(zbm@PeBpbWQ/)2~
```

Memberships per the plan (alice is a real admin via the built-in `admins`
group; `ipausers` is the default "users" group):

```
ipa group-add-member admins      --users=alice
ipa group-add-member vpn-users   --users=alice,bob
ipa group-add-member api-writers --users=carol
ipa group-add-member ipausers    --users=dave
```

## 5. Export the CA certificate (S1.5)

Both tracks need this for TLS trust (REST API, internal HTTPS, client enroll):

```
sudo cp /etc/ipa/ca.crt /tmp/kyber-ipa-ca.crt
```

Copy `kyber-ipa-ca.crt` off the VM (e.g. `scp` via the jump host) and commit it
to the repo under `services/ldap/` so app-01/app-02/mon-01 can trust it.

## 6. Test plan (S1.6 acceptance)

```
# 1. Kerberos + GSSAPI LDAP as a test user (S1.6 acceptance)
kdestroy
kinit alice                       # forces a password change on first login
klist                             # -> alice@KYBER.LOCAL TGT
ldapsearch -Y GSSAPI -b "dc=kyber,dc=local" "(uid=alice)" memberOf
#   -> shows cn=admins,... and cn=vpn-users,cn=groups,cn=accounts,dc=kyber,dc=local

# 2. Anonymous simple bind (proves plain LDAP works)
ldapsearch -x -H ldap://kyber-ldap.kyber.local \
  -b "cn=users,cn=accounts,dc=kyber,dc=local" "(uid=bob)" uid mail

# 3. SRV discovery records (proves integrated DNS healthy)
dig @127.0.0.1 _kerberos._tcp.kyber.local SRV +short
dig @127.0.0.1 _ldap._tcp.kyber.local      SRV +short   # -> kyber-ldap.kyber.local.

# 4. Forward + reverse resolution
dig @127.0.0.1 kyber-ldap.kyber.local A    +short   # -> 192.168.7.30
dig @127.0.0.1 kyber-ldap.kyber.local AAAA +short   # -> 2001:1470:fffd:99::30
dig @127.0.0.1 -x 192.168.7.30             +short   # -> kyber-ldap.kyber.local.
```

## 7. Firewall for dns

```
sudo firewall-cmd --permanent --add-service=dns
sudo firewall-cmd --permanent --add-port={80,443,389,636,88,464}/tcp
sudo firewall-cmd --permanent --add-port={88,464}/udp
sudo firewall-cmd --reload
```
