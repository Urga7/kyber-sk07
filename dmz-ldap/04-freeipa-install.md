# FreeIPA install (LDAP VM — AlmaLinux 10)

## 1. Install packages

```
sudo dnf -y install ipa-server ipa-server-dns ipa-healthcheck
```

There are no debconf-style realm/admin-server prompts here (that was Debian's
`freeipa-server`); the realm is set entirely by `ipa-server-install`.

## 2. Run the installer

Interactive the first time so the prompts are visible:

```
sudo ipa-server-install \
  --realm=KYBER.LOCAL \
  --domain=kyber.local \
  --hostname=kyber-ldap.kyber.local \
  --ip-address=192.168.7.30 \
  --setup-dns \
  --auto-forwarders \
  --no-reverse \
  --no-ntp
```

Flag rationale:
- `--setup-dns` — turn on integrated BIND9.
- `--auto-forwarders` — uses `/etc/resolv.conf` (1.1.1.1) as upstream forwarder.
  Use `--forwarder=192.168.7.1` instead if you want split-horizon via VyOS.
- `--no-reverse` — skip auto reverse zone; `7.168.192.in-addr.arpa` is added
  explicitly post-install (it's already referenced in the VyOS forwarder).
- `--no-ntp` — leave chrony alone; it was pointed at VyOS in `02`.

You will be prompted for a **Directory Manager** password (≥ 8 chars; low-level
LDAP root) and an **admin** password (the IPA `admin` user). Store both in a
password manager.

The installer takes 10–20 min. Success ends with
`The ipa-server-install command was successful`.

## 3. Verify

```
kinit admin                           # then klist -> TGT for admin@KYBER.LOCAL
sudo ipactl status                    # all services should report RUNNING
sudo ipa-healthcheck --failures-only  # EL10 diagnostic; no output = healthy
dig @127.0.0.1 _ldap._tcp.kyber.local SRV +short   # -> kyber-ldap.kyber.local
```

Immediately back up `/root/cacert.p12` somewhere safe — it holds the IPA CA
private key and is required to stand up a replica later.

Post-install object setup (reverse zone, DMZ host A-records, groups, test
users, CA-cert export) and the full test plan are tracked in `ldap-setup.txt`
and become the next runbook once executed.
