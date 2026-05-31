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

`commit` + `save`. Then confirm `snmpd` actually came up and bound the DMZ addresses — a
running service that isn't listening (or that never got committed) is the usual cause of a
"Timeout: No Response":

```
sudo systemctl --no-pager status snmpd
sudo ss -lnup | grep ':161'    # expect 192.168.7.1:161 AND [2001:1470:fffd:99::1]:161
```

> **Test from mon, not from the router.** The community is scoped to `192.168.7.20/32`, so
> only mon may query. A self-test from `kyber-rtr` to its own `192.168.7.1` leaves with
> source `192.168.7.1` (the kernel uses the destination's own address for locally-destined
> traffic), which the ACL rejects — snmpd silently drops it and you get a timeout that looks
> like a firewall problem but isn't. Verify from mon instead (and that exercises the N6
> `udp/161` `DMZ→LOCAL` rule + the `state-policy` return path):
>
> ```
> # on kyber-mon
> snmpwalk -v2c -c kyber-ro 192.168.7.1 .1.3.6.1.2.1.1.1.0   # -> "VyOS 1.4.4"
> ```