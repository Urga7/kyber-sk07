```
set system host-name 'rtr-kyber'
set system domain-name 'kyber.sk07.lrk'

# Temporary public resolvers until we stand up our own DNS.
# We'll replace these in the DNS phase.
set system name-server '1.1.1.1'
set system name-server '8.8.8.8'

set interfaces ethernet eth1 address '10.7.0.1/24'
set interfaces ethernet eth2 address '192.168.7.1/24'
```