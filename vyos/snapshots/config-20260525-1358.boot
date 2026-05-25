interfaces {
    ethernet eth0 {
        address "88.200.24.237/25"
        address "2001:1470:fffd:98::2/64"
        description "Public-Network"
        hw-id "00:0c:29:07:45:54"
    }
    ethernet eth1 {
        address "10.7.0.1/24"
        address "2001:1470:fffd:9a::1/64"
        description "Internal-Network"
        hw-id "00:0c:29:07:45:5e"
    }
    ethernet eth2 {
        address "192.168.7.1/24"
        address "2001:1470:fffd:99::1/64"
        description "DMZ-Network"
        hw-id "00:0c:29:07:45:68"
    }
    ethernet eth3 {
        address "fd07:1:1:1::1/64"
        description "IPv6-Only-Network"
        hw-id "00:0c:29:07:45:72"
    }
    loopback lo {
    }
}
nat {
    source {
        rule 100 {
            description "Masquerade DMZ to WAN"
            outbound-interface {
                name "eth0"
            }
            source {
                address "192.168.7.0/24"
            }
            translation {
                address "masquerade"
            }
        }
        rule 110 {
            description "Masquerade Internal to WAN"
            outbound-interface {
                name "eth0"
            }
            source {
                address "10.7.0.0/24"
            }
            translation {
                address "masquerade"
            }
        }
    }
}
nat66 {
    destination {
        rule 10 {
            description "NPTv6 ipv6only inbound"
            destination {
                address "2001:1470:fffd:9b::/64"
            }
            inbound-interface {
                name "eth0"
            }
            translation {
                address "fd07:1:1:1::/64"
            }
        }
    }
    source {
        rule 10 {
            description "NPTv6 ipv6only outbound"
            outbound-interface {
                name "eth0"
            }
            source {
                prefix "fd07:1:1:1::/64"
            }
            translation {
                address "2001:1470:fffd:9b::/64"
            }
        }
    }
}
protocols {
    static {
        route 0.0.0.0/0 {
            next-hop 88.200.24.129 {
            }
        }
        route6 ::/0 {
            next-hop 2001:1470:fffd:98::1 {
            }
        }
    }
}
service {
    dhcp-server {
        shared-network-name DMZ {
            subnet 192.168.7.0/24 {
                default-router "192.168.7.1"
                name-server "192.168.7.1"
                static-mapping app-01 {
                    ip-address "192.168.7.10"
                    mac-address "00:0C:29:AA:AA:10"
                }
                static-mapping ldap {
                    ip-address "192.168.7.30"
                    mac-address "00:0C:29:82:FB:06"
                }
            }
        }
        shared-network-name INTERNAL {
            subnet 10.7.0.0/24 {
                default-router "10.7.0.1"
                domain-name "kyber.local"
                name-server "10.7.0.1"
                range 0 {
                    start "10.7.0.100"
                    stop "10.7.0.200"
                }
            }
        }
    }
    dhcpv6-server {
        shared-network-name DMZ6 {
            subnet 2001:1470:fffd:99::/64 {
                domain-search "kyber.local"
                name-server "2001:1470:fffd:99::1"
                static-mapping app-01 {
                    identifier "00:03:00:01:00:0C:29:AA:AA:10"
                    ipv6-address "2001:1470:fffd:99::10"
                }
                static-mapping ldap {
                    identifier "00:03:00:01:00:0C:29:82:FB:06"
                    ipv6-address "2001:1470:fffd:99::30"
                }
            }
        }
        shared-network-name INTERNAL6 {
            subnet 2001:1470:fffd:9a::/64 {
                address-range {
                    start 2001:1470:fffd:9a::100 {
                        stop "2001:1470:fffd:9a::1ff"
                    }
                }
                domain-search "kyber.local"
                name-server "2001:1470:fffd:9a::1"
            }
        }
    }
    dns {
        forwarding {
            allow-from "10.7.0.0/24"
            allow-from "192.168.7.0/24"
            allow-from "fd07:1:1:1::/64"
            allow-from "2001:1470:fffd:98::/62"
            domain 7.168.192.in-addr.arpa {
                name-server 192.168.7.30 {
                }
            }
            domain kyber.local {
                addnta
                name-server 192.168.7.30 {
                }
                name-server 2001:1470:fffd:99::30 {
                }
                recursion-desired
            }
            listen-address "10.7.0.1"
            listen-address "192.168.7.1"
            listen-address "fd07:1:1:1::1"
            listen-address "2001:1470:fffd:99::1"
            listen-address "2001:1470:fffd:9a::1"
            name-server 1.0.0.1 {
            }
            name-server 1.1.1.1 {
            }
            name-server 2606:4700:4700::1001 {
            }
            name-server 2606:4700:4700::1111 {
            }
            no-serve-rfc1918
        }
    }
    ntp {
        allow-client {
            address "127.0.0.0/8"
            address "169.254.0.0/16"
            address "10.0.0.0/8"
            address "172.16.0.0/12"
            address "192.168.0.0/16"
            address "::1/128"
            address "fe80::/10"
            address "fc00::/7"
            address "2001:1470:fffd:9a::/64"
            address "2001:1470:fffd:99::/64"
            address "192.168.7.0/24"
            address "10.7.0.0/24"
        }
        server 1.europe.pool.ntp.org {
        }
        server 1.si.pool.ntp.org {
        }
        server ntp1.arnes.si {
        }
        server ntp2.arnes.si {
        }
    }
    router-advert {
        interface eth1 {
            managed-flag
            other-config-flag
            prefix 2001:1470:fffd:9a::/64 {
                no-autonomous-flag
            }
        }
        interface eth2 {
            managed-flag
            other-config-flag
            prefix 2001:1470:fffd:99::/64 {
                no-autonomous-flag
            }
        }
        interface eth3 {
            default-preference "medium"
            prefix fd07:1:1:1::/64 {
            }
        }
    }
    ssh {
        disable-password-authentication
    }
}
system {
    config-management {
        commit-revisions "100"
    }
    conntrack {
        modules {
            ftp
            h323
            nfs
            pptp
            sip
            sqlnet
            tftp
        }
    }
    console {
        device ttyS0 {
            speed "115200"
        }
    }
    domain-name "kyber.local"
    host-name "kyber-rtr"
    login {
        user vyos {
            authentication {
                encrypted-password "$6$rounds=656000$Yswh0KcGlD5U7yPy$.gfJzXUTMq530LFipfAe1nVn0D6t3Zt9bNFhnb8ncwhvLJzgJZTERdPUe922bGCsM/L36NkJDh7uaEo.w2sEe."
                plaintext-password ""
                public-keys desktop-pc-saturn {
                    key "AAAAC3NzaC1lZDI1NTE5AAAAIMQxC3q6bGjehahRourdtrGvM8GnFqD/0KnuRUMymPrh"
                    type "ssh-ed25519"
                }
                public-keys luka-laptop {
                    key "AAAAC3NzaC1lZDI1NTE5AAAAID+iVqMJTY66DBlSCZMzZjjQbs7wZDF4QPaSEJw6y3Xp"
                    type "ssh-ed25519"
                }
                public-keys urban-laptop {
                    key "AAAAC3NzaC1lZDI1NTE5AAAAIETuSEFMw73ojxO8FLlon2c8B3WkB1kUCCjOXgZZn/6D"
                    type "ssh-ed25519"
                }
            }
        }
    }
    name-server "1.1.1.1"
    name-server "8.8.8.8"
    syslog {
        global {
            facility all {
                level "info"
            }
            facility local7 {
                level "debug"
            }
        }
    }
    time-zone "Europe/Ljubljana"
}


// Warning: Do not remove the following line.
// vyos-config-version: "bgp@6:broadcast-relay@1:cluster@2:config-management@1:conntrack@6:conntrack-sync@2:container@2:dhcp-relay@2:dhcp-server@8:dhcpv6-server@1:dns-dynamic@4:dns-forwarding@4:firewall@15:flow-accounting@1:https@6:ids@1:interfaces@33:ipoe-server@3:ipsec@13:isis@3:l2tp@9:lldp@2:mdns@1:monitoring@1:nat@8:nat66@3:ntp@3:openconnect@3:ospf@2:pim@1:policy@8:pppoe-server@10:pptp@5:qos@2:quagga@11:reverse-proxy@1:rip@1:rpki@2:salt@1:snmp@3:ssh@2:sstp@6:system@27:vrf@3:vrrp@4:vyos-accel-ppp@2:wanloadbalance@3:webproxy@2"
// Release version: 1.4.4