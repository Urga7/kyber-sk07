# Quick test commands

A one-page command cheatsheet for the kyber (sk07) network. Run from an internal
client (`ws-01`/`ws-02`) or any host that trusts the FreeIPA CA and uses the internal
resolver. For the full, annotated acceptance suite see the other files in `tests/`.

Constants: API `api.kyber.local` → VIP `192.168.7.100` / `2001:1470:fffd:99::100`;
FreeIPA/DNS `kyber-ldap` → `192.168.7.30` / `::30`.

## DNS resolution

```bash
dig +short api.kyber.local                          # -> 192.168.7.100 (VIP)
dig +short api.kyber.local AAAA                      # -> 2001:1470:fffd:99::100
dig +short kyber-ldap.kyber.local A                  # -> 192.168.7.30
dig +short kyber-ldap.kyber.local AAAA               # -> 2001:1470:fffd:99::30
dig +short -x 192.168.7.30                           # -> kyber-ldap.kyber.local.  (reverse)
dig +short _ldap._tcp.kyber.local SRV                # -> ... kyber-ldap.kyber.local.
```

## Test API availability

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health   # 200, no TLS error
curl -4 https://api.kyber.local/health                                    # over IPv4
curl -6 https://api.kyber.local/health                                    # over IPv6
curl -s -o /dev/null -w 'HTTP/%{http_version} %{http_code}\n' --http2 https://api.kyber.local/customers   # HTTP/2 200
```

> Use a plain **GET** (not `curl -I`): routes are GET-only and HEAD returns `405`.

## Content negotiation (S3.3)

```bash
curl -H 'Accept: application/json' https://api.kyber.local/customers      # JSON array
curl -H 'Accept: application/xml'  https://api.kyber.local/customers      # <customers><customer>…
curl -H 'Accept: text/html'        https://api.kyber.local/customers      # rendered table
```

## Authenticated writes (S3.8)

```bash
# No creds -> 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.kyber.local/orders

# dave (not in api-writers) -> 403
curl -u dave:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"total":9.99}'

# carol (in api-writers) -> 201 created
curl -u carol:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' -d '{"customer_id":1,"total":9.99}'
```

## TLS certificate

```bash
openssl s_client -connect api.kyber.local:443 -servername api.kyber.local </dev/null 2>/dev/null \
  | openssl x509 -noout -issuer -subject -dates                          # issuer = FreeIPA CA
```
