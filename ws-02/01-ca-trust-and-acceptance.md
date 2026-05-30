# kyber-ws-02 — trust the IPA CA & run REST API acceptance tests (Windows)

Imports the FreeIPA CA into the Windows **Trusted Root** store so `https://api.kyber.local`
validates in `curl.exe`, PowerShell, and Edge/Chrome, then runs the
[`dmz-app-01/03-rest-api.md`](../dmz-app-01/03-rest-api.md) §6 acceptance suite from Windows.

> **Prerequisites:** [`00-os-install-config.md`](00-os-install-config.md) done
> (`api.kyber.local` resolves to the private DMZ IP); and the API stood up — `03-rest-api.md`
> §1–5 complete, `ipa-getcert list` at **MONITORING**, nginx reloaded.

## 1. Get the CA cert onto the box

The CA is committed at [`dmz-ldap/kyber-ipa-ca.crt`](../dmz-ldap/kyber-ipa-ca.crt). Copy that
file over (RDP clipboard / shared folder), **or** recreate it locally — it is a public
certificate. In an **elevated** PowerShell:

```powershell
@'
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
'@ | Set-Content -Path C:\kyber-ipa-ca.crt -Encoding ascii
```

## 2. Import into Trusted Root (LocalMachine)

```powershell
Import-Certificate -FilePath C:\kyber-ipa-ca.crt -CertStoreLocation Cert:\LocalMachine\Root
# equivalent: certutil -addstore -f Root C:\kyber-ipa-ca.crt
```

This is the machine-wide **Schannel** trust store, so `curl.exe`, `Invoke-WebRequest`, Edge,
and Chrome all trust the CA. (Firefox, if installed, uses its own store — import there
separately for browser tests in Firefox.) Verify the import:

```powershell
Get-ChildItem Cert:\LocalMachine\Root | Where-Object { $_.Subject -like '*KYBER.LOCAL*' }
```

## 3. Acceptance suite (`03-rest-api.md` §6) with `curl.exe`

Windows ships `curl.exe`, which uses the Schannel backend and therefore the store from §2.
Call it as **`curl.exe`** explicitly — bare `curl` is a PowerShell alias for
`Invoke-WebRequest` and takes different arguments.

**Content negotiation (S3.3):**

```powershell
curl.exe -H "Accept: application/json" https://api.kyber.local/customers
curl.exe -H "Accept: application/xml"  https://api.kyber.local/customers
curl.exe -H "Accept: text/html"        https://api.kyber.local/customers
```

**IPv6 (S3.9):**

```powershell
curl.exe -6 https://api.kyber.local/health
```

**Auth (S3.8) — `carol` ∈ `api-writers`, `dave` ∉ (replace `PASS`):**

```powershell
# Write the JSON to a file and feed it with -d "@file" — do NOT pass it inline.
'{"customer_id":1,"product":"Bolt","quantity":5,"amount":4.95}' | Set-Content -Encoding ascii order.json
curl.exe -X POST https://api.kyber.local/orders -H "Content-Type: application/json" -d "@order.json"                  # 401
curl.exe -u dave:'8Bd:.3(zbm@PeBpbWQ/)2~'  -X POST https://api.kyber.local/orders -H "Content-Type: application/json" -d "@order.json"    # 403
curl.exe -u carol:'1Xs~9dnhoJ(@K>BL;fjZ~Y' -X POST https://api.kyber.local/orders -H "Content-Type: application/json" -d "@order.json"    # 201
```

> **Don't pass the JSON inline as `-d $body`.** Windows PowerShell strips the embedded
> double quotes before `curl.exe` sees them, so the API receives `{customer_id:1,…}`
> (unquoted keys) and returns `422` *"Expecting property name enclosed in double quotes"*.
> Reading the body from a file with `-d "@order.json"` sidesteps the quoting entirely.
> (The PowerShell-native `Invoke-RestMethod` alternative below has no such issue.)

> **HTTP/2 caveat.** The bundled Windows `curl.exe` uses Schannel and is usually built
> *without* nghttp2 — `curl.exe -V` won't list `HTTP2`, and `--http2` silently falls back to
> HTTP/1.1. Don't rely on it for the S3.6 check. Verify `h2` from `ws-01` (Linux) instead, or
> open `https://api.kyber.local/customers` in Edge/Chrome DevTools → Network → the
> **Protocol** column shows `h2`. The other §6 checks are unaffected.
