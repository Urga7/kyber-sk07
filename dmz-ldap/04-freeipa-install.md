# FreeIPA install (LDAP VM — AlmaLinux 10)

## 1. Install packages

```
sudo dnf -y install ipa-server ipa-server-dns ipa-healthcheck
```

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
- `--setup-dns` — turn on FreeIPA-integrated BIND. **This box becomes the
  authoritative DNS for `kyber.local`** (project decision 2026-05-18; the
  standalone-BIND9-on-app-01 idea in plan S2 is dropped). VyOS N4.2 forwards
  the `kyber.local` zone here — `192.168.7.30` / `2001:1470:fffd:99::30`.
- `--auto-forwarders` — reads `/etc/resolv.conf`; here that is **both**
  `1.1.1.1` and `2001:1470:fffd:99::1` (already dual-stack via N3.4), used as
  upstream forwarders for everything *outside* `kyber.local`. To send external
  resolution through VyOS instead (consistent with the plan's designated
  forwarder, `kyber-project-plan.md` N4.1), drop this flag and pass
  `--forwarder=192.168.7.1 --forwarder=2001:1470:fffd:99::1`.
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
