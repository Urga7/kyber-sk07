interfaces {
    ethernet eth0 {
        address "88.200.24.237/25"
        description "Public-Network"
        hw-id "00:0c:29:07:45:54"
    }
    ethernet eth1 {
        address "10.7.0.1/24"
        description "Internal-Network"
        hw-id "00:0c:29:07:45:5e"
    }
    ethernet eth2 {
        address "192.168.7.1/24"
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
    }
}
service {
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
        }
        server time1.vyos.net {
        }
        server time2.vyos.net {
        }
        server time3.vyos.net {
        }
    }
    router-advert {
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
    domain-name "kyber.sk07.lrk"
    host-name "rtr-kyber"
    login {
        user vyos {
            authentication {
                encrypted-password "$6$rounds=656000$Yswh0KcGlD5U7yPy$.gfJzXUTMq530LFipfAe1nVn0D6t3Zt9bNFhnb8ncwhvLJzgJZTERdPUe922bGCsM/L36NkJDh7uaEo.w2sEe."
                plaintext-password ""
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
}


// Warning: Do not remove the following line.
// vyos-config-version: "bgp@6:broadcast-relay@1:cluster@2:config-management@1:conntrack@6:conntrack-sync@2:container@2:dhcp-relay@2:dhcp-server@8:dhcpv6-server@1:dns-dynamic@4:dns-forwarding@4:firewall@15:flow-accounting@1:https@6:ids@1:interfaces@33:ipoe-server@3:ipsec@13:isis@3:l2tp@9:lldp@2:mdns@1:monitoring@1:nat@8:nat66@3:ntp@3:openconnect@3:ospf@2:pim@1:policy@8:pppoe-server@10:pptp@5:qos@2:quagga@11:reverse-proxy@1:rip@1:rpki@2:salt@1:snmp@3:ssh@2:sstp@6:system@27:vrf@3:vrrp@4:vyos-accel-ppp@2:wanloadbalance@3:webproxy@2"
// Release version: 1.4.4
