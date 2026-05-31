snmp_exporter needs an SNMP agent to talk to. Expose SNMPv2c with a
read-only community **restricted to mon's IP**.

```
set service snmp community kyber-ro authorization ro
set service snmp community kyber-ro network '192.168.7.20/32'
set service snmp listen-address 192.168.7.1
set service snmp listen-address 2001:1470:fffd:99::1
set service snmp contact 'sk07'
set service snmp location 'kyber-lab'
```