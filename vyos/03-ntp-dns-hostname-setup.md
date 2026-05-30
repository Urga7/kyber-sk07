### NTP server
- `delete service ntp server time1.vyos.net`
- `delete service ntp server time2.vyos.net`
- `delete service ntp server time3.vyos.net` 
- `set service ntp server ntp1.arnes.si`
- `set service ntp server ntp2.arnes.si`
- `set service ntp server 1.si.pool.ntp.org`
- `set service ntp server 1.europe.pool.ntp.org`
- `set service ntp allow-client address '192.168.7.0/24'`
- `set service ntp allow-client address '10.7.0.0/24'`
- `set service ntp allow-client address '2001:1470:fffd:99::/64'`
- `set service ntp allow-client address '2001:1470:fffd:9a::/64'`
- `set service ntp allow-client address 'fd07:1:1:1::/64'`

- `set system time-zone Europe/Ljubljana`

### DHCPv4 NTP advertisement (internal + DMZ)
- `set service dhcp-server shared-network-name INTERNAL subnet 10.7.0.0/24 ntp-server '10.7.0.1'`
- `set service dhcp-server shared-network-name DMZ subnet 192.168.7.0/24 ntp-server '192.168.7.1'`

### DHCPv6 NTP advertisement (internal + DMZ)
- `set service dhcpv6-server shared-network-name INTERNAL6 subnet 2001:1470:fffd:9a::/64 sntp-server '2001:1470:fffd:9a::1'`
- `set service dhcpv6-server shared-network-name DMZ6 subnet 2001:1470:fffd:99::/64 sntp-server '2001:1470:fffd:99::1'`

### Stateless DHCPv6 on ipv6-only (NTP only)
- `set service router-advert interface eth3 other-config-flag`
- `set service dhcpv6-server shared-network-name V6ONLY subnet fd07:1:1:1::/64 sntp-server 'fd07:1:1:1::1'`

### DNS split
- `set service dns forwarding name-server '1.1.1.1'`
- `set service dns forwarding name-server '1.0.0.1'`
- `set service dns forwarding name-server '2606:4700:4700::1111'`
- `set service dns forwarding name-server '2606:4700:4700::1001'`
- `set service dns forwarding domain kyber.local name-server '192.168.7.30'`
- `set service dns forwarding domain kyber.local name-server '2001:1470:fffd:99::30'`
- `set service dns forwarding domain kyber.local addnta`
- `set service dns forwarding domain kyber.local recursion-desired`
- `set service dns forwarding domain 7.168.192.in-addr.arpa name-server '192.168.7.30'`
- `delete service dns forwarding domain 0.7.10.in-addr.arpa`
- `set service dns forwarding listen-address '10.7.0.1'`
- `set service dns forwarding listen-address '192.168.7.1'`
- `set service dns forwarding listen-address 'fd07:1:1:1::1'`
- `set service dns forwarding listen-address '2001:1470:fffd:99::1'`
- `set service dns forwarding listen-address '2001:1470:fffd:9a::1'`
- `set service dns forwarding allow-from '10.7.0.0/24'`
- `set service dns forwarding allow-from '192.168.7.0/24'`
- `set service dns forwarding allow-from 'fd07:1:1:1::/64'`
- `set service dns forwarding allow-from '2001:1470:fffd:99::/64'`
- `set service dns forwarding allow-from '2001:1470:fffd:9a::/64'`
- `set service dns forwarding no-serve-rfc1918`

### DNS advertisement on ipv6-only (RDNSS via SLAAC)
- `set service router-advert interface eth3 name-server 'fd07:1:1:1::1'`
- `set service router-advert interface eth3 dnssl 'kyber.local'`
