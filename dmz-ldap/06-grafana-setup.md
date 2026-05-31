```
kinit admin
ipa dnsrecord-add kyber.local grafana --a-rec=192.168.7.20 --aaaa-rec=2001:1470:fffd:99::20
ipa host-add grafana.kyber.local --force
ipa service-add HTTP/grafana.kyber.local
ipa service-add-host HTTP/grafana.kyber.local --hosts=kyber-mon.kyber.local
```