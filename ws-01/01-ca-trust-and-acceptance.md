# kyber-ws-01 — trust the IPA CA & run REST API acceptance tests (Linux)

Installs the FreeIPA CA into the system trust store so HTTPS to `https://api.kyber.local`
validates, then runs the [`dmz-app-01/03-rest-api.md`](../dmz-app-01/03-rest-api.md) §6
acceptance suite from the Linux client.

> **Prerequisites:** [`00-os-install-config.md`](00-os-install-config.md) done (DHCP up,
> `api.kyber.local` resolves to the private DMZ IP); and the API itself stood up —
> `03-rest-api.md` §1–5 complete, `ipa-getcert list` at **MONITORING**, nginx reloaded.

## 1. Install the CA cert into the system trust store

The CA is committed at [`dmz-ldap/kyber-ipa-ca.crt`](../dmz-ldap/kyber-ipa-ca.crt). Either
copy that file onto the box, or paste it (it is a public certificate). The destination
**must** be under `/usr/local/share/ca-certificates/` with a `.crt` extension:

```
sudo tee /usr/local/share/ca-certificates/kyber-ipa-ca.crt >/dev/null <<'CRT'
-----BEGIN CERTIFICATE-----
MIIEWTCCAsGgAwIBAgIQWzQNjQ7cRKQaGoEJUXdpQjANBgkqhkiG9w0BAQsFADA2
MRQwEgYDVQQKDAtLWUJFUi5MT0NBTDEeMBwGA1UEAwwVQ2VydGlmaWNhdGUgQXV0
aG9yaXR5MB4XDTI2MDUyMzEzMTUxOVoXDTQ2MDUyMzEzMTUxOVowNjEUMBIGA1UE
CgwLS1lCRVIuTE9DQUwxHjAcBgNVBAMMFUNlcnRpZmljYXRlIEF1dGhvcml0eTCC
AaIwDQYJKoZIhvcNAQEBBQADggGPADCCAYoCggGBAOcVsXAVIieOI/Y2+XcV+itV
J9CVns95tu0p23mnU/Vo+6EXvDij8/PE0LTRczCjkU9LP9lAjmIo3vpmovT8YwPl
imNMLlqjlveVeRgFOyqDyKgP2tQSiMRvgzcucZ2nOflTDaBUmhW5+jZss/7LS7Mu
hTHBNbgGsW4PP6uuYv1iv/9XE1Dgi0Po0xCVvYReJBPGPsRZokjmF//d87f6UcON
blMGi+1i4pqXDgvx5nSsS7r5XywQfugPjwnUU16koW6XhuCM0bGN1AWKP8da0EGO
EFADPe4QyvFB1duOQqgqO3bHeDyII3n91Q+WwK8ZUNfWC2NFLp7kL17H5DADnOHD
tbAwAtQjXTVeEqguUUZLZ+a6YEyhR3gZ/n1k2L+sEl3S9xVZNbx+i/OaB9FnQ3aN
jM85LNsNEVwTECDr4pMZcohp1uVKrSocnxZQ0Ws9uCaLRbkMAQess1dOjepTp+Z5
pQv4Pz3vb38Tk7h6yQnAGAI62w/ZDQShS1HBjwnreQIDAQABo2MwYTAdBgNVHQ4E
FgQUMQTXG7GgR/uG0NdLl5isMqOKs/MwHwYDVR0jBBgwFoAUMQTXG7GgR/uG0NdL
l5isMqOKs/MwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAcYwDQYJKoZI
hvcNAQELBQADggGBAOEW0FZlpiPDycHnW1BmWhZXgDhGaKpA+MCTM3Q/Ow9pxbdW
I9oYByY6Z2cA5woySzQkvrmT/AwZpmwVZM87LZU/HIvIRGiFv3TQOxE7ukFOsR3G
mNZXEOdU9iIC6Ujwxb4ARXy+Lml5oS3prA70APDm9b9Gxteg7ggjyR1r1chedVh2
bYe473lRLRpcErLhDA6G6qHWIjBSRni0qN0uaRDNZfcaR1GTvGLgyq4rNfdtUA5A
oACnQCH+Lq1QZ0z8EwKY/qtt/7D6iAeljZI4JnmYCunVydsReQ7y1E9BQI5xO2Md
ZLPivawiZHwGJlQpMUJjra3OObQY4fA9YwPd0FlcobX9CMpLNq9J5cr4+t+hC989
u2bCq4lHpAfJkAI+X/4MX4k/o7BNLBFKxfv2UfGjtONmSFFuNwjs1K1qTw2m+EwJ
39w4mPjuGeg4AymLKWnl/kTPjjGbjO1dJt6Z5r5hKruDI4t1Tizu3YRl1esk7gsc
GZsMg1hZ1w93s3AkVw==
-----END CERTIFICATE-----
CRT
sudo update-ca-certificates        # expect "1 added"
```

This rebuilds `/etc/ssl/certs`, the store used by `curl`, `wget`, `openssl`, and Python's
`ldap3`.

> Firefox/Chromium on the desktop maintain their **own** NSS trust store, separate from the
> system one. For in-browser testing, import the same `.crt` under Settings → Privacy &
> Security → Certificates → View Certificates → Authorities → Import. `curl` (used below)
> reads the system store and needs no extra step.

## 2. Verify trust

```
curl -s -o /dev/null -w '%{http_code}\n' https://api.kyber.local/health   # 200, and no TLS error
openssl s_client -connect api.kyber.local:443 -servername api.kyber.local </dev/null 2>/dev/null \
  | grep -i 'verify return code'                 # 0 (ok)
```

> Use a plain **GET** here, not `curl -I` — `-I` sends a HEAD request and `/health`
> is GET-only, so it answers `405 Method Not Allowed`. A `405` (or any HTTP status)
> still proves trust succeeded: an untrusted CA aborts before any response with
> `curl: (60) SSL certificate problem` and prints no status line.

If you see *"unable to get local issuer certificate"* / *"self-signed certificate in chain"*,
the CA is not trusted yet — recheck §1 (correct path, `.crt` extension, `update-ca-certificates` ran).

## 3. Acceptance suite (`03-rest-api.md` §6)

**Content negotiation (S3.3):**

```
curl -H 'Accept: application/json' https://api.kyber.local/customers     # JSON array
curl -H 'Accept: application/xml'  https://api.kyber.local/customers     # <customers><customer>…
curl -H 'Accept: text/html'        https://api.kyber.local/customers     # rendered table
```

**HTTP/2 (S3.6) — confirm the negotiated protocol is `h2`:**

```
curl -s -o /dev/null -w 'HTTP/%{http_version} %{http_code}\n' --http2 https://api.kyber.local/customers   # HTTP/2 200
```

> Again a **GET**, not `curl -I`: `/customers` is GET-only and HEAD returns `405`.
> The negotiated `HTTP/2` is what this step verifies — the status code is incidental.

**IPv6 (S3.9) — the client's `9a::` lease routes to the API's `99::10`:**

```
curl -6 https://api.kyber.local/health
```

**Auth (S3.8) — `carol` ∈ `api-writers`, `dave` ∉ (replace `PASS` with each user's password):**

```
curl -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' \
  -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'      # 401 (no auth)
curl -u dave:"8Bd:.3(zbm@PeBpbWQ/)2~"  -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' \
  -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'      # 403 (not api-writers)
curl -u carol:PASS -X POST https://api.kyber.local/orders \
  -H 'Content-Type: application/json' \
  -d '{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}'      # 201 (created)
```

> The IPv6 check needs the VyOS firewall to permit **internal → DMZ** on 443 over *both*
> stacks. If `curl -4` succeeds but `curl -6` hangs, the problem is routing/firewall between
> the internal (`9a::`) and DMZ (`99::`) segments, not the client — verify on the router.
