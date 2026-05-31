## 1. FreeIPA prep (on `kyber-ldap`, as admin)

Create a **low-privilege bind account** for the VPN (a FreeIPA *system account* — not a real
user, not admin) and make sure the `vpn-users` group exists with your test members.

```sh
kinit admin
# vpn-users group + members already exist per S1.3 (alice, bob). Verify:
ipa group-show vpn-users

# Dedicated read-only bind account under cn=sysaccounts (FreeIPA has no ipa-CLI for these):
ldapmodify -x -D "cn=Directory Manager" -W <<'LDIF'
dn: uid=vpn-bind,cn=sysaccounts,cn=etc,dc=kyber,dc=local
changetype: add
objectclass: account
objectclass: simplesecurityobject
uid: vpn-bind
userPassword: <CHOOSE-A-STRONG-BIND-PASSWORD>
passwordExpirationTime: 20380119031407Z
nsIdleTimeout: 0
LDIF
```

> `# test on kyber-ldap — ldapwhoami -x -H ldaps://kyber-ldap.kyber.local -D uid=vpn-bind,cn=sysaccounts,cn=etc,dc=kyber,dc=local -w '<pw>' — returns the bind DN`

## 2. Put the IPA CA + LDAP bind config on the router (secrets — NOT committed)

Copy the committed IPA CA cert to the router and write the plugin's LDAP config. Both live in
`/config/auth/` on the box; the config holds the bind password, so it is **never committed**
(template is in [`/services/vpn/ldap-auth.config.example`](../services/vpn/ldap-auth.config.example)).

```sh
# from your workstation, via the jump-host:
scp -J vyos@88.200.24.237 dmz-ldap/kyber-ipa-ca.crt vyos@88.200.24.237:/tmp/kyber-ipa-ca.crt
```
```sh
# on kyber-rtr:
sudo mkdir -p /config/auth
sudo mv /tmp/kyber-ipa-ca.crt /config/auth/kyber-ipa-ca.crt
sudo tee /config/auth/ldap-auth.config >/dev/null <<'CFG'
<LDAP>
  # STARTTLS on 389: use ldap:// WITH TLSEnable yes. Do NOT combine ldaps:// (implicit
  # TLS/636) with TLSEnable yes — the plugin would try STARTTLS over an SSL socket and fail
  # with "Unable to enable STARTTLS: Can't contact LDAP server".
  URL             ldap://kyber-ldap.kyber.local
  BindDN          uid=vpn-bind,cn=sysaccounts,cn=etc,dc=kyber,dc=local
  Password        <THE-BIND-PASSWORD-FROM-§1>
  Timeout         15
  TLSEnable       yes
  TLSCACertFile   /config/auth/kyber-ipa-ca.crt
  FollowReferrals no
</LDAP>
<Authorization>
  BaseDN          "cn=users,cn=accounts,dc=kyber,dc=local"
  # %u = the username the client typed. The memberOf clause enforces vpn-users LIVE:
  SearchFilter    "(&(uid=%u)(memberOf=cn=vpn-users,cn=groups,cn=accounts,dc=kyber,dc=local))"
  RequireGroup    false
</Authorization>
CFG
sudo chmod 600 /config/auth/ldap-auth.config
```


