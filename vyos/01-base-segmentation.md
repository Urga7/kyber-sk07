```
set system host-name 'kyber-rtr-01'
set system domain-name 'kyber.local'

# Temporary public resolvers until replaced in the DNS phase
set system name-server '1.1.1.1'
set system name-server '8.8.8.8'

set interfaces ethernet eth0 address 88.200.24.237/25
set interfaces ethernet eth0 address 2001:1470:fffd:98::2/64

set interfaces ethernet eth1 address 10.7.0.1/24
set interfaces ethernet eth1 address 2001:1470:fffd:9a::/64

set interfaces ethernet eth2 address 192.168.7.1/24
set interfaces ethernet eth2 address 2001:1470:fffd:99::/64

set interfaces ethernet eth3 address fd07:1:1:1::1/64

set protocols static route 0.0.0.0/0 next-hop 88.200.24.129
set protocols static route6 ::/0 next-hop 2001:1470:fffd:98::1
```