## Setup interface names (Example)

- `show interfaces ethernet eth2`
- `configure`
- `set interfaces ethernet eth2 description 'DMZ-Network'`
- `commit`
- `save`
- `exit`

## IPv6 segments

```
2001:1470:fffd:98::/64 → WAN (eth0, link to LRK)
2001:1470:fffd:99::/64 → Internal (eth1)
2001:1470:fffd:9a::/64 → DMZ (eth2)
2001:1470:fffd:9b::/64 → NPTv6 external side (eth3)
```

