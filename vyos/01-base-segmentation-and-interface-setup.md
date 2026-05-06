```
set system host-name 'kyber-rtr'
set system domain-name 'kyber.local'
```

```
# Temporary public resolvers until replaced in the DNS phase
set system name-server '1.1.1.1'
set system name-server '8.8.8.8'
```

```
set interfaces ethernet eth0 address 88.200.24.237/25
set interfaces ethernet eth0 address 2001:1470:fffd:98::2/64
set interfaces ethernet eth0 description 'Public-Network'
```

```
set interfaces ethernet eth1 address 10.7.0.1/24
set interfaces ethernet eth1 address 2001:1470:fffd:9a::1/64
set interfaces ethernet eth1 description 'Internal-Network'
```

```
set interfaces ethernet eth2 address 192.168.7.1/24
set interfaces ethernet eth2 address 2001:1470:fffd:99::1/64
set interfaces ethernet eth2 description 'DMZ-Network'
```

```
set interfaces ethernet eth3 address fd07:1:1:1::1/64
set interfaces ethernet eth3 description 'IPv6-Only-Network'
```

```
set protocols static route 0.0.0.0/0 next-hop 88.200.24.129
set protocols static route6 ::/0 next-hop 2001:1470:fffd:98::1
```

## IPv6 segments

```
2001:1470:fffd:98::/64 → WAN (eth0, link to LRK)
2001:1470:fffd:9a::/64 → Internal (eth1)
2001:1470:fffd:99::/64 → DMZ (eth2)
2001:1470:fffd:9b::/64 → NPTv6 external side (eth3)
```