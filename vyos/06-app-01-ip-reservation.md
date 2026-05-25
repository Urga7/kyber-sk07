```
set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 static-mapping app-01 ip-address '192.168.7.10'
set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 static-mapping app-01 mac-address '00:0C:29:a9:04:71'
set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping app-01 ipv6-address '2001:1470:fffd:99::10'
set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping app-01 identifier '00:03:00:01:00:0C:29:a9:04:71'
```