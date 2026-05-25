# LDAP / User Directory (S1.7)

User directory for `kyber.local`, provided by **FreeIPA** (LDAP + Kerberos +
internal CA) on `kyber-ldap` — AlmaLinux 10, `192.168.7.30` /
`2001:1470:fffd:99::30`, DMZ segment.

FreeIPA backs LDAP with **389 Directory Server**. The base DN is
`dc=kyber,dc=local` and the realm is `KYBER.LOCAL`.

Build and post-install steps are in the numbered runbooks in this directory
(`00-`…`05-`). The internal CA certificate is exported as
[`kyber-ipa-ca.crt`](kyber-ipa-ca.crt) — app-01, app-02, and mon-01 trust it
for TLS.

## Directory tree

FreeIPA places all accounts under `cn=accounts`. The containers relevant to
this project:

```
dc=kyber,dc=local
└── cn=accounts
    ├── cn=users,cn=accounts          # all user entries
    │   ├── uid=admin                 # built-in IPA admin
    │   ├── uid=alice
    │   ├── uid=bob
    │   ├── uid=carol
    │   └── uid=dave
    └── cn=groups,cn=accounts         # all POSIX groups
        ├── cn=admins                 # built-in — real administrators
        ├── cn=ipausers               # built-in "users" group (default)
        ├── cn=vpn-users
        └── cn=api-writers
```

(FreeIPA also creates `cn=computers`, `cn=services`, `cn=dns`, `cn=ca`, etc.
under `cn=accounts`/`dc=kyber,dc=local`; only the account containers above are
project-managed.)

## User DNs

| User  | DN                                                       | Groups                |
|-------|----------------------------------------------------------|-----------------------|
| alice | `uid=alice,cn=users,cn=accounts,dc=kyber,dc=local`       | admins, vpn-users     |
| bob   | `uid=bob,cn=users,cn=accounts,dc=kyber,dc=local`         | vpn-users             |
| carol | `uid=carol,cn=users,cn=accounts,dc=kyber,dc=local`       | api-writers           |
| dave  | `uid=dave,cn=users,cn=accounts,dc=kyber,dc=local`        | ipausers (default)    |

## Group DNs

| Group       | DN                                                        | Purpose                          |
|-------------|-----------------------------------------------------------|----------------------------------|
| admins      | `cn=admins,cn=groups,cn=accounts,dc=kyber,dc=local`       | Full directory administration    |
| ipausers    | `cn=ipausers,cn=groups,cn=accounts,dc=kyber,dc=local`     | Default group for all users      |
| vpn-users   | `cn=vpn-users,cn=groups,cn=accounts,dc=kyber,dc=local`    | Allowed to connect via WireGuard |
| api-writers | `cn=api-writers,cn=groups,cn=accounts,dc=kyber,dc=local`  | Can call REST API write endpoints|

## Integration points

- **Host login / enrollment** — DMZ/internal hosts enroll via `ipa-client`;
  the CA cert above is the trust anchor.
- **VPN (Track N)** — WireGuard authorizes members of `vpn-users`.
- **REST API (Track S)** — write endpoints require `api-writers`; auth via
  Kerberos/GSSAPI or LDAP bind.
- **Internal DNS** — FreeIPA owns the authoritative `kyber.local` zone and the
  `7.168.192.in-addr.arpa.` reverse zone on this host (see runbook `05`, §1–2).
  The VyOS forwarder points at `192.168.7.30`.

## Acceptance (S1.6)

Verified per runbook `05` §6: GSSAPI `ldapsearch` as `alice` returns
`cn=admins` + `cn=vpn-users` in `memberOf`; anonymous simple bind, SRV
discovery, and forward/reverse resolution all succeed.
