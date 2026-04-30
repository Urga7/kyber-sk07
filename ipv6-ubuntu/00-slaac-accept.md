### Accept IPv6 over SLAAC
- `sudo vi /etc/netplan/50-cloud-init.yaml`
    ```
    network:
      version: 2
      renderer: networkd
      ethernets:
        ens160:
          dhcp4: false
          dhcp6: false
          accept-ra: true
          link-local: [ ipv6 ]
    ```
- `sudo chmod 600 /etc/netplan/50-cloud-init.yaml (though this didnt seem to work)`
- `sudo netplan try`
- `sudo netplan apply`
