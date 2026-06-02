# Test 04 — FreeIPA directory + internal authoritative DNS (S1, S2)

Validates the on-prem user directory on `kyber-ldap` (FreeIPA: LDAP + Kerberos + CA) and the
FreeIPA-integrated authoritative DNS for `kyber.local`. Mirrors the acceptance in
`dmz-ldap/04-freeipa-install.md` §4 and `dmz-ldap/05-freeipa-postinstall.md` §6.

**Where to run:** `kyber-ldap` for server-side checks; any enrolled host (`kyber-app-01`) for the
client view. Covers **S1.1–S1.6, S2.1–S2.6**.

> Domain `kyber.local`, realm `KYBER.LOCAL`, base `dc=kyber,dc=local`. Users `alice/bob/carol/
> dave` (+ team `luka/urban`); groups: built-in `admins`/`ipausers` plus `vpn-users`,
> `api-writers`. CA cert committed at `dmz-ldap/kyber-ipa-ca.crt`.

---

## 1. Services healthy (S1.1)

**Run on:** `kyber-ldap`

```
sudo ipactl status                       # every service RUNNING (DS, KDC, named, httpd, ca, ...)
sudo ipa-healthcheck --failures-only     # no output = healthy
```

**Expect:** all FreeIPA services `RUNNING`; healthcheck clean.

**Output:**
As expected

## 2. Authenticate + group memberships (S1.4, S1.6)

**Run on:** `kyber-ldap`

```
kdestroy
kinit carol                              # (first login forces a password change)
klist                                    # -> carol@KYBER.LOCAL TGT
ldapsearch -Y GSSAPI -b "dc=kyber,dc=local" "(uid=carol)" memberOf
```

**Expect:** `carol` shows `cn=api-writers,cn=groups,cn=accounts,dc=kyber,dc=local`. Repeat for:

| User | Expected group(s) |
|---|---|
| `alice` | `admins`, `vpn-users` |
| `bob` | `vpn-users`, `ipausers` |
| `carol` | `api-writers` |
| `dave` | `ipausers` (and **not** `api-writers`, **not** `vpn-users`) |
| `luka`, `urban` | `vpn-users` |

```
ipa group-show api-writers               # member: carol
ipa group-show vpn-users                 # members: alice, bob, luka, urban
```

**Output:**
As expected

## 3. LDAPS bind works; CA is the committed one (S1.5, S3.8 precondition)

**Run on:** `kyber-app-01` (enrolled, trusts the IPA CA via the system store)

```
ldapwhoami -H ldaps://kyber-ldap.kyber.local -D "uid=carol,cn=users,cn=accounts,dc=kyber,dc=local" -W
openssl s_client -connect kyber-ldap.kyber.local:636 </dev/null 2>/dev/null | openssl x509 -noout -issuer
```

## 4. Internal authoritative DNS — forward, reverse, SRV (S2)

**Run on:** `kyber-ldap` (query the FreeIPA BIND directly on `127.0.0.1`)

```
dig @127.0.0.1 kyber-ldap.kyber.local A     +short    # -> 192.168.7.30
dig @127.0.0.1 kyber-ldap.kyber.local AAAA  +short    # -> 2001:1470:fffd:99::30
dig @127.0.0.1 api.kyber.local A            +short    # -> 192.168.7.100 (VIP)
dig @127.0.0.1 -x 192.168.7.30              +short    # -> kyber-ldap.kyber.local.
dig @127.0.0.1 _ldap._tcp.kyber.local  SRV  +short    # -> ... kyber-ldap.kyber.local.
dig @127.0.0.1 _kerberos._tcp.kyber.local SRV +short  # -> ... kyber-ldap.kyber.local.
```

**Expect:** forward A/AAAA, reverse PTR (zone `7.168.192.in-addr.arpa`), and IPA SRV records all
resolve. These are the records the VyOS forwarder hands `kyber.local` queries to (N4.2).

**Output:**
As expected

**Run on:** `kyber-app-01` — prove the split path end-to-end (host → VyOS → FreeIPA):

```
dig +short api.kyber.local                  # -> 192.168.7.100  (resolved via 192.168.7.1 -> .30)
```

**Expect:** an ordinary DMZ host, using the router as resolver, gets the **private** answer —
the split-DNS requirement (also exercised from internal in [`01-router-networking.md`](01-router-networking.md) §6).

**Output:**
As expected

## 5. Anonymous simple bind (directory is browsable for non-secret attrs)

**Run on:** `kyber-ldap`

```
ldapsearch -x -H ldap://kyber-ldap.kyber.local \
  -b "cn=users,cn=accounts,dc=kyber,dc=local" "(uid=bob)" uid mail
```

**Expect:** returns `bob`'s entry — confirms plain LDAP read works for the integration points
(the REST API auth path in [`05-rest-api.md`](05-rest-api.md) §7 uses an authenticated LDAPS bind).

**Output:**
As expected
