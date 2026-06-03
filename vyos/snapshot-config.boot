firewall {
    global-options {
        state-policy {
            established {
                action "accept"
            }
            invalid {
                action "drop"
            }
            related {
                action "accept"
            }
        }
    }
    ipv4 {
        name DMZ-INTERNAL {
            default-action "drop"
            default-log
        }
        name DMZ-LOCAL {
            default-action "drop"
            rule 20 {
                action "accept"
                description "DNS forwarder"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 21 {
                action "accept"
                description "NTP relay"
                destination {
                    port "123"
                }
                protocol "udp"
            }
            rule 22 {
                action "accept"
                description "DHCPv4 renew"
                destination {
                    port "67"
                }
                protocol "udp"
            }
            rule 24 {
                action "accept"
                description "SNMP from mon only"
                destination {
                    port "161"
                }
                protocol "udp"
                source {
                    address "192.168.7.20"
                }
            }
            rule 26 {
                action "accept"
                icmp {
                    type-name "echo-request"
                }
                protocol "icmp"
            }
        }
        name DMZ-WAN {
            default-action "drop"
            rule 20 {
                action "accept"
                description "HTTP (apt)"
                destination {
                    port "80"
                }
                protocol "tcp"
            }
            rule 21 {
                action "accept"
                description "HTTPS (apt/pip/CRL)"
                destination {
                    port "443"
                }
                protocol "tcp"
            }
            rule 22 {
                action "accept"
                description "NTP fallback"
                destination {
                    port "123"
                }
                protocol "udp"
            }
            rule 23 {
                action "accept"
                icmp {
                    type-name "echo-request"
                }
                protocol "icmp"
            }
        }
        name INTERNAL-DMZ {
            default-action "drop"
            rule 20 {
                action "accept"
                description "HTTPS (REST API, Grafana)"
                destination {
                    port "443"
                }
                protocol "tcp"
            }
            rule 21 {
                action "accept"
                description "SSH admin to DMZ"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 30 {
                action "accept"
                description "DNS to FreeIPA"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 31 {
                action "accept"
                description "LDAPS"
                destination {
                    port "636"
                }
                protocol "tcp"
            }
            rule 35 {
                action "accept"
                description "HTTP to FreeIPA CA — CRL/OCSP revocation (Schannel)"
                destination {
                    address "192.168.7.30"
                    port "80"
                }
                protocol "tcp"
            }
        }
        name INTERNAL-LOCAL {
            default-action "drop"
            rule 20 {
                action "accept"
                description "SSH mgmt"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 30 {
                action "accept"
                description "DNS forwarder"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 31 {
                action "accept"
                description "NTP relay"
                destination {
                    port "123"
                }
                protocol "udp"
            }
            rule 32 {
                action "accept"
                description "DHCPv4"
                destination {
                    port "67"
                }
                protocol "udp"
            }
            rule 33 {
                action "accept"
                description "ping gateway"
                icmp {
                    type-name "echo-request"
                }
                protocol "icmp"
            }
        }
        name INTERNAL-WAN {
            default-action "accept"
        }
        name LOCAL-OUT {
            default-action "accept"
        }
        name VPN-DMZ {
            default-action "drop"
            rule 20 {
                action "accept"
                description "HTTPS (REST API, Grafana)"
                destination {
                    port "443"
                }
                protocol "tcp"
            }
            rule 21 {
                action "accept"
                description "SSH to DMZ servers"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 22 {
                action "accept"
                description "LDAPS"
                destination {
                    port "636"
                }
                protocol "tcp"
            }
        }
        name VPN-INTERNAL {
            default-action "drop"
            rule 20 {
                action "accept"
                description "SSH to internal hosts"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 21 {
                action "accept"
                description "RDP to ws-02"
                destination {
                    port "3389"
                }
                protocol "tcp"
            }
            rule 22 {
                action "accept"
                description "ping diag"
                icmp {
                    type-name "echo-request"
                }
                protocol "icmp"
            }
        }
        name VPN-LOCAL {
            default-action "drop"
            rule 20 {
                action "accept"
                description "SSH mgmt"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 30 {
                action "accept"
                description "DNS"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 31 {
                action "accept"
                description "NTP"
                destination {
                    port "123"
                }
                protocol "udp"
            }
        }
        name WAN-DMZ {
            default-action "drop"
            default-log
            rule 20 {
                action "accept"
                description "HTTPS / REST API"
                destination {
                    port "443"
                }
                protocol "tcp"
            }
        }
        name WAN-INTERNAL {
            default-action "drop"
            default-log
        }
        name WAN-LOCAL {
            default-action "drop"
            rule 20 {
                action "accept"
                icmp {
                    type-name "echo-request"
                }
                protocol "icmp"
            }
            rule 30 {
                action "accept"
                description "OpenVPN endpoint (N7)"
                destination {
                    port "1194"
                }
                protocol "udp"
            }
        }
    }
    ipv6 {
        name DMZ-INTERNAL6 {
            default-action "drop"
            default-log
        }
        name DMZ-LOCAL6 {
            default-action "drop"
            rule 20 {
                action "accept"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 21 {
                action "accept"
                destination {
                    port "123"
                }
                protocol "udp"
            }
            rule 23 {
                action "accept"
                description "DHCPv6"
                destination {
                    port "547"
                }
                protocol "udp"
            }
            rule 24 {
                action "accept"
                description "SNMP from mon only"
                destination {
                    port "161"
                }
                protocol "udp"
                source {
                    address "2001:1470:fffd:99::20"
                }
            }
            rule 26 {
                action "accept"
                protocol "ipv6-icmp"
            }
        }
        name DMZ-WAN6 {
            default-action "drop"
            rule 20 {
                action "accept"
                destination {
                    port "80"
                }
                protocol "tcp"
            }
            rule 21 {
                action "accept"
                destination {
                    port "443"
                }
                protocol "tcp"
            }
            rule 22 {
                action "accept"
                destination {
                    port "123"
                }
                protocol "udp"
            }
            rule 23 {
                action "accept"
                protocol "ipv6-icmp"
            }
        }
        name INTERNAL-DMZ6 {
            default-action "drop"
            rule 20 {
                action "accept"
                destination {
                    port "443"
                }
                protocol "tcp"
            }
            rule 21 {
                action "accept"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 30 {
                action "accept"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 31 {
                action "accept"
                destination {
                    port "636"
                }
                protocol "tcp"
            }
            rule 35 {
                action "accept"
                description "HTTP to FreeIPA CA — CRL/OCSP revocation (Schannel)"
                destination {
                    address "2001:1470:fffd:99::30"
                    port "80"
                }
                protocol "tcp"
            }
        }
        name INTERNAL-LOCAL6 {
            default-action "drop"
            rule 20 {
                action "accept"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 30 {
                action "accept"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 31 {
                action "accept"
                destination {
                    port "123"
                }
                protocol "udp"
            }
            rule 32 {
                action "accept"
                description "DHCPv6"
                destination {
                    port "547"
                }
                protocol "udp"
            }
            rule 33 {
                action "accept"
                description "NDP + ping"
                protocol "ipv6-icmp"
            }
        }
        name INTERNAL-WAN6 {
            default-action "accept"
        }
        name LOCAL-OUT6 {
            default-action "accept"
        }
        name V6ONLY-LOCAL6 {
            default-action "drop"
            rule 20 {
                action "accept"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 21 {
                action "accept"
                destination {
                    port "123"
                }
                protocol "udp"
            }
            rule 22 {
                action "accept"
                protocol "ipv6-icmp"
            }
        }
        name V6ONLY-WAN6 {
            default-action "accept"
        }
        name VPN-DMZ6 {
            default-action "drop"
            rule 20 {
                action "accept"
                destination {
                    port "443"
                }
                protocol "tcp"
            }
            rule 21 {
                action "accept"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 22 {
                action "accept"
                destination {
                    port "636"
                }
                protocol "tcp"
            }
        }
        name VPN-INTERNAL6 {
            default-action "drop"
            rule 20 {
                action "accept"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 21 {
                action "accept"
                destination {
                    port "3389"
                }
                protocol "tcp"
            }
            rule 22 {
                action "accept"
                protocol "ipv6-icmp"
            }
        }
        name VPN-LOCAL6 {
            default-action "drop"
            rule 20 {
                action "accept"
                destination {
                    port "22"
                }
                protocol "tcp"
            }
            rule 30 {
                action "accept"
                destination {
                    port "53"
                }
                protocol "tcp_udp"
            }
            rule 31 {
                action "accept"
                destination {
                    port "123"
                }
                protocol "udp"
            }
        }
        name WAN-DMZ6 {
            default-action "drop"
            default-log
            rule 20 {
                action "accept"
                destination {
                    address "2001:1470:fffd:99::100"
                    port "443"
                }
                protocol "tcp"
            }
        }
        name WAN-INTERNAL6 {
            default-action "drop"
            default-log
        }
        name WAN-LOCAL6 {
            default-action "drop"
            rule 20 {
                action "accept"
                description "icmpv6 (echo + NDP)"
                protocol "ipv6-icmp"
            }
            rule 30 {
                action "accept"
                description "OpenVPN endpoint"
                destination {
                    port "1194"
                }
                protocol "udp"
            }
        }
    }
    zone DMZ {
        default-action "drop"
        from INTERNAL {
            firewall {
                ipv6-name "INTERNAL-DMZ6"
                name "INTERNAL-DMZ"
            }
        }
        from LOCAL {
            firewall {
                ipv6-name "LOCAL-OUT6"
                name "LOCAL-OUT"
            }
        }
        from VPN {
            firewall {
                ipv6-name "VPN-DMZ6"
                name "VPN-DMZ"
            }
        }
        from WAN {
            firewall {
                ipv6-name "WAN-DMZ6"
                name "WAN-DMZ"
            }
        }
        interface "eth2"
    }
    zone INTERNAL {
        default-action "drop"
        from DMZ {
            firewall {
                ipv6-name "DMZ-INTERNAL6"
                name "DMZ-INTERNAL"
            }
        }
        from LOCAL {
            firewall {
                ipv6-name "LOCAL-OUT6"
                name "LOCAL-OUT"
            }
        }
        from VPN {
            firewall {
                ipv6-name "VPN-INTERNAL6"
                name "VPN-INTERNAL"
            }
        }
        from WAN {
            firewall {
                ipv6-name "WAN-INTERNAL6"
                name "WAN-INTERNAL"
            }
        }
        interface "eth1"
    }
    zone LOCAL {
        default-action "drop"
        from DMZ {
            firewall {
                ipv6-name "DMZ-LOCAL6"
                name "DMZ-LOCAL"
            }
        }
        from INTERNAL {
            firewall {
                ipv6-name "INTERNAL-LOCAL6"
                name "INTERNAL-LOCAL"
            }
        }
        from V6ONLY {
            firewall {
                ipv6-name "V6ONLY-LOCAL6"
            }
        }
        from VPN {
            firewall {
                ipv6-name "VPN-LOCAL6"
                name "VPN-LOCAL"
            }
        }
        from WAN {
            firewall {
                ipv6-name "WAN-LOCAL6"
                name "WAN-LOCAL"
            }
        }
        local-zone
    }
    zone V6ONLY {
        default-action "drop"
        from LOCAL {
            firewall {
                ipv6-name "LOCAL-OUT6"
            }
        }
        interface "eth3"
    }
    zone VPN {
        default-action "drop"
        from LOCAL {
            firewall {
                ipv6-name "LOCAL-OUT6"
                name "LOCAL-OUT"
            }
        }
        interface "vtun0"
    }
    zone WAN {
        default-action "drop"
        from DMZ {
            firewall {
                ipv6-name "DMZ-WAN6"
                name "DMZ-WAN"
            }
        }
        from INTERNAL {
            firewall {
                ipv6-name "INTERNAL-WAN6"
                name "INTERNAL-WAN"
            }
        }
        from LOCAL {
            firewall {
                ipv6-name "LOCAL-OUT6"
                name "LOCAL-OUT"
            }
        }
        from V6ONLY {
            firewall {
                ipv6-name "V6ONLY-WAN6"
            }
        }
        interface "eth0"
    }
}
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
    openvpn vtun0 {
        device-type "tun"
        encryption {
            cipher "aes256gcm"
        }
        hash "sha256"
        keep-alive {
            failure-count "6"
            interval "10"
        }
        local-port "1194"
        mode "server"
        openvpn-option "--plugin /usr/lib/openvpn/openvpn-auth-ldap.so /config/auth/ldap-auth.config"
        openvpn-option "--verify-client-cert none"
        openvpn-option "--username-as-common-name"
        persistent-tunnel
        protocol "udp"
        server {
            name-server "10.7.99.1"
            push-route 10.7.0.0/24 {
            }
            push-route 192.168.7.0/24 {
            }
            push-route 2001:1470:fffd:9a::/64 {
            }
            push-route 2001:1470:fffd:99::/64 {
            }
            subnet "10.7.99.0/24"
            subnet "fd07:99::/64"
        }
        tls {
            ca-certificate "ca-vpn"
            certificate "srv-vpn"
        }
    }
}
nat {
    destination {
        rule 100 {
            description "DNAT WAN:443 -> HA VIP (I1)"
            destination {
                address "88.200.24.237"
                port "443"
            }
            inbound-interface {
                name "eth0"
            }
            protocol "tcp"
            translation {
                address "192.168.7.100"
            }
        }
    }
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
pki {
    ca ca-vpn {
        certificate "MIIDnzCCAoegAwIBAgIUEb2fKro+1zGRiXf+8q5GTQ5b0h8wDQYJKoZIhvcNAQELBQAwWDELMAkGA1UEBhMCU0kxEzARBgNVBAgMClNvbWUtU3RhdGUxEjAQBgNVBAcMCUxqdWJsamFuYTENMAsGA1UECgwEVnlPUzERMA8GA1UEAwwIa3liZXIuaW8wHhcNMjYwNTMxMTcxNjQxWhcNMzEwNTMwMTcxNjQxWjBYMQswCQYDVQQGEwJTSTETMBEGA1UECAwKU29tZS1TdGF0ZTESMBAGA1UEBwwJTGp1YmxqYW5hMQ0wCwYDVQQKDARWeU9TMREwDwYDVQQDDAhreWJlci5pbzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKfeQY06C2HhpPPDIy8AdDq0a3ATO5KmK1gixRH7ZrLYYURmY2apoURmiSzbqhvjBNSgoNDWQMitt0AXgH36s/Llh8iNpQgLZwHhkqgwtIwbV2ut+/hDlbOhJ3kgeFDgt40qNtyQ7W0HkOSeRBbnmtkweQ6q15tq0t6Ix+SwOV3kj6kfN6Fjj4WQ3ZIRd+3kuwXviFm0FvI526qNStF0oAZ6pduzQn3FNQatdnC4j3C4/EbDw9xXYtdrXQ4FF/zkVtWuVTivCWLKrsambH24MfWAkJwtPJGelREXKOHkEGwwbUZhs7FstHCT3g4aogkNkXynzuLfj8IOQR631w2diRUCAwEAAaNhMF8wDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAYYwHQYDVR0lBBYwFAYIKwYBBQUHAwIGCCsGAQUFBwMBMB0GA1UdDgQWBBSihUgy5knVwKAgKDsgw7W/rux85TANBgkqhkiG9w0BAQsFAAOCAQEAGVFov8F4IQTKMkErhGoYBahjBRLSjvkbBzkYvWMPVbDsp0wKiULUEhmyZ9hxbC7wbG+xyGja4Jj8KXeQkvCqTJ7R3VfWDoRXjoBL5Xwv9MLjM+x9p4rRBmicHBq6TdoKcvcI5ZYRv3z9Q6zz4gVvsgagVZLEylyqKKn+ZLf8GyRwVXVdithXvHBFmHXXRvh+HErtJSIZ+EEPl+sCp13grlDlLYOP65jIK7A0MGM20FvG0Wv+NDpvMgwXIqUu7kl3Mf/LXPJsrHBuQ2wCCmMh6EFiRTdG/Dyi/RXdjWDLOeRLHIL4xyosFBHE4HTqnJ9PCKhH7WgZbGJBskPf5rNQsQ=="
        private {
            key "<REDACTED>"
        }
    }
    certificate srv-vpn {
        certificate "MIIDszCCApugAwIBAgIUXv73qK+rcxaomL7w3T9Z4Pgbjo4wDQYJKoZIhvcNAQELBQAwWDELMAkGA1UEBhMCU0kxEzARBgNVBAgMClNvbWUtU3RhdGUxEjAQBgNVBAcMCUxqdWJsamFuYTENMAsGA1UECgwEVnlPUzERMA8GA1UEAwwIa3liZXIuaW8wHhcNMjYwNTMxMTcyMTM2WhcNMjcwNTMxMTcyMTM2WjBYMQswCQYDVQQGEwJTSTETMBEGA1UECAwKU29tZS1TdGF0ZTESMBAGA1UEBwwJTGp1YmxqYW5hMQ0wCwYDVQQKDARWeU9TMREwDwYDVQQDDAhreWJlci5pbzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALNQg68wJZIT/K9w4IbkQ8idieOj+5cEqNQlpVqMpBWlp4gHbwP5VZWImINPkjnYDqJUdo/QNQbCigjF8eVkggm6328n3u+Mk4fCHB5X99QbR8+e7tEvE3ROTxQBWL5MuJXI3dcE3Er8Rb5Bv1+o4fL8ojNCf3hQgxHFXNqKCfAhzFbhHhltABDrz+5MTICTH05EeCEIlgVHlRAYyVvkCun1JAcH3wLXygeGlqM7ylmjTrC3UQXNpSDfr2F1a8mnRS3Sy+88la1XIOn8KQtXOM3Y9+mNLvKY5esvcefWk7j47wJ9MJJfru7pzJ7Tnnm7f02ziuXhfCTqgwAnHjQ+G4sCAwEAAaN1MHMwDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwEwYDVR0lBAwwCgYIKwYBBQUHAwEwHQYDVR0OBBYEFAiW/4+TihCk1shOpNQdOxHmZyn1MB8GA1UdIwQYMBaAFKKFSDLmSdXAoCAoOyDDtb+u7HzlMA0GCSqGSIb3DQEBCwUAA4IBAQCnegda3KJsINCS7v6bNyOYU7qpjo6GzfSnoZ/8LqQUwbOiGkL+XOd1pkZa0CR7CB9z8QNMpI35bgNkI2zRg4AtH+sohCNRN4WN4Za0qlWpk4t/C78wdnSy20AptPx1kh7nALFDTrF6pA2fGjNjo4s4Ck8GYfyVa73l59rLgFLst94qtdnuisrkJ6YZXOaNZkxPNrfDrmHOeN6muBMBj/6vQm8Jif8i0wvcD8zGz1SkgDdrbQcGQFLtFCwpMDmQb5H8VQBJh6RxX5rEwhAOAu4HnX592xJ0o/oTRuJqJ2Md9BFrB3Aq723p+KvrCfQVkUWLrQIAvHaxtM9sVdRk1PSm"
        private {
            key "<REDACTED>"
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
                ntp-server "192.168.7.1"
                static-mapping app-01 {
                    ip-address "192.168.7.10"
                    mac-address "00:0c:29:a9:04:71"
                }
                static-mapping app-02 {
                    ip-address "192.168.7.11"
                    mac-address "00:0C:29:E3:A7:80"
                }
                static-mapping ldap {
                    ip-address "192.168.7.30"
                    mac-address "00:0C:29:82:FB:06"
                }
                static-mapping mon {
                    ip-address "192.168.7.20"
                    mac-address "00:0C:29:1D:A9:6E"
                }
            }
        }
        shared-network-name INTERNAL {
            subnet 10.7.0.0/24 {
                default-router "10.7.0.1"
                domain-name "kyber.local"
                name-server "10.7.0.1"
                ntp-server "10.7.0.1"
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
                sntp-server "2001:1470:fffd:99::1"
                static-mapping app-01 {
                    identifier "00:03:00:01:00:0c:29:a9:04:71"
                    ipv6-address "2001:1470:fffd:99::10"
                }
                static-mapping app-02 {
                    identifier "00:03:00:01:00:0C:29:E3:A7:80"
                    ipv6-address "2001:1470:fffd:99::11"
                }
                static-mapping ldap {
                    identifier "00:03:00:01:00:0C:29:82:FB:06"
                    ipv6-address "2001:1470:fffd:99::30"
                }
                static-mapping mon {
                    identifier "00:03:00:01:00:0C:29:1D:A9:6E"
                    ipv6-address "2001:1470:fffd:99::20"
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
                sntp-server "2001:1470:fffd:9a::1"
            }
        }
        shared-network-name V6ONLY {
            subnet fd07:1:1:1::/64 {
                sntp-server "fd07:1:1:1::1"
            }
        }
    }
    dns {
        forwarding {
            allow-from "10.7.0.0/24"
            allow-from "192.168.7.0/24"
            allow-from "fd07:1:1:1::/64"
            allow-from "2001:1470:fffd:99::/64"
            allow-from "2001:1470:fffd:9a::/64"
            allow-from "10.7.99.0/24"
            allow-from "fd07:99::/64"
            allow-from "127.0.0.0/8"
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
            listen-address "10.7.99.1"
            listen-address "fd07:99::1"
            listen-address "127.0.0.1"
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
            address "fd07:1:1:1::/64"
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
            dnssl "kyber.local"
            name-server "fd07:1:1:1::1"
            other-config-flag
            prefix fd07:1:1:1::/64 {
            }
        }
    }
    snmp {
        community kyber-ro {
            authorization "ro"
            network "192.168.7.20/32"
            network "2001:1470:fffd:99::20/128"
        }
        contact "sk07"
        listen-address 192.168.7.1 {
        }
        listen-address 2001:1470:fffd:99::1 {
        }
        location "kyber-lab"
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
    flow-accounting {
        interface "eth0"
        interface "eth1"
        interface "eth2"
        interface "eth3"
        netflow {
            sampling-rate "1"
            server 192.168.7.20 {
                port "2055"
            }
            source-address "192.168.7.1"
            version "9"
        }
    }
    host-name "kyber-rtr"
    login {
        user vyos {
            authentication {
                encrypted-password "<REDACTED>"
                plaintext-password "<REDACTED>"
                public-keys desktop-pc-saturn {
                    key "<REDACTED>"
                    type "ssh-ed25519"
                }
                public-keys luka-laptop {
                    key "<REDACTED>"
                    type "ssh-ed25519"
                }
                public-keys urban-laptop {
                    key "<REDACTED>"
                    type "ssh-ed25519"
                }
            }
        }
    }
    name-server "127.0.0.1"
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
