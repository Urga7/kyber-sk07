## Split and reserve IPv6 segment for IPv6 traffic, set up SLAAC
```
2001:1470:fffd:9b::/64 → NPTv6 external side (mapped to eth3)

ULA used: fd07:1:1:1::/64
```
- `configure`
- `set interfaces ethernet eth3 address 'fd07:1:1:1::1/64'`
- `set service router-advert interface eth3 prefix fd07:1:1:1::/64`
- `set service router-advert interface eth3 default-preference 'medium'`
- `set nat66 source rule 10 description 'NPTv6 ipv6only outbound'`
- `set nat66 source rule 10 outbound-interface name 'eth0'`
- `set nat66 source rule 10 source prefix 'fd07:1:1:1::/64'`
- `set nat66 source rule 10 translation address '2001:1470:fffd:9b::/64'`
- `set nat66 destination rule 10 description 'NPTv6 ipv6only inbound'`
- `set nat66 destination rule 10 inbound-interface name 'eth0'`
- `set nat66 destination rule 10 destination address '2001:1470:fffd:9b::/64'`
- `set nat66 destination rule 10 translation address 'fd07:1:1:1::/64'`
- `commit`
- `save`
- `exit`

