set nat source rule 100 description 'Masquerade DMZ to WAN'
set nat source rule 100 outbound-interface name 'eth0'
set nat source rule 100 source address '192.168.7.0/24'
set nat source rule 100 translation address 'masquerade'

set nat source rule 110 description 'Masquerade Internal to WAN'
set nat source rule 110 outbound-interface name 'eth0'
set nat source rule 110 source address '10.7.0.0/24'
set nat source rule 110 translation address 'masquerade'
