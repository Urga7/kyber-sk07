### Accept IPv6 over SLAAC
```
echo 'network: {config: disabled}' | sudo tee /etc/cloud/cloud.cfg.d/99-disable-network-config.cfg
sudo rm -f /etc/netplan/50-cloud-init.yaml
sudo tee /etc/netplan/90-ipv6.yaml >/dev/null <<'EOF'
network:
  version: 2
  renderer: networkd
  ethernets:
    ens160:
      dhcp4: false
      dhcp6: false
      accept-ra: true
      link-local: [ ipv6 ]
      nameservers:
        addresses: [ "fd07:1:1:1::1" ]
        search: [ kyber.local ]
EOF
sudo chmod 600 /etc/netplan/90-ipv6.yaml
sudo netplan generate && sudo netplan apply
```
