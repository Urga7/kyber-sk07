### DHCPv4 for Internal Network
- `configure`
- `set service dhcp-server shared-network-name INTERNAL subnet 10.7.0.0/24 default-router '10.7.0.1'`
- `set service dhcp-server shared-network-name INTERNAL subnet 10.7.0.0/24 domain-name 'kyber.local'`
- `set service dhcp-server shared-network-name INTERNAL subnet 10.7.0.0/24 range 0 start '10.7.0.100'`
- `set service dhcp-server shared-network-name INTERNAL subnet 10.7.0.0/24 range 0 stop '10.7.0.200'`
- `set service dhcp-server shared-network-name INTERNAL subnet 10.7.0.0/24 name-server '10.7.0.1'`
- `commit`
- `save`

### DHCPv4 for DMZ with static reservations
- `configure`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 default-router '192.168.7.1'`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 name-server '192.168.7.1'`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 static-mapping ldap ip-address '192.168.7.30'`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 static-mapping ldap mac-address '00:0C:29:82:FB:06'`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 static-mapping app-01 ip-address '192.168.7.10'`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 static-mapping app-01 mac-address '00:0C:29:A9:04:71'`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 static-mapping mon ip-address '192.168.7.20'`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 static-mapping mon mac-address '00:0C:29:1D:A9:6E'`
- `commit`
- `save`

### DHCPv6 for Internal Network
- `configure`
- `set service dhcpv6-server shared-network-name INTERNAL6 subnet 2001:1470:fffd:9a::/64 address-range start 2001:1470:fffd:9a::100 stop 2001:1470:fffd:9a::1ff`
- `set service dhcpv6-server shared-network-name INTERNAL6 subnet 2001:1470:fffd:9a::/64 name-server 2001:1470:fffd:9a::1`
- `set service dhcpv6-server shared-network-name INTERNAL6 subnet 2001:1470:fffd:9a::/64 domain-search kyber.local`
- `set service router-advert interface eth1 managed-flag`
- `set service router-advert interface eth1 other-config-flag`
- `set service router-advert interface eth1 prefix 2001:1470:fffd:9a::/64`
- `set service router-advert interface eth1 prefix 2001:1470:fffd:9a::/64 no-autonomous-flag`
- `commit`
- `save`

### DHCPv6 for DMZ with static reservations
- `configure`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 name-server '2001:1470:fffd:99::1'`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 domain-search 'kyber.local'`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping ldap ipv6-address '2001:1470:fffd:99::30'`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping ldap identifier '00:03:00:01:00:0C:29:82:FB:06'`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping app-01 ipv6-address '2001:1470:fffd:99::10'`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping app-01 identifier '00:03:00:01:00:0C:29:A9:04:71'`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping mon ipv6-address '2001:1470:fffd:99::20'`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 static-mapping mon identifier '00:03:00:01:00:0C:29:1D:A9:6E'`
- `set service router-advert interface eth2 managed-flag`
- `set service router-advert interface eth2 other-config-flag`
- `set service router-advert interface eth2 prefix 2001:1470:fffd:99::/64`
- `set service router-advert interface eth2 prefix 2001:1470:fffd:99::/64 no-autonomous-flag`
- `commit`
- `save`
