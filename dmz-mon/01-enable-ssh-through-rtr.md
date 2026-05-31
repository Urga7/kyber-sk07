# SSH access through the router

```
sudo apt -y install openssh-server
sudo systemctl enable --now ssh
```

Ubuntu's `ufw` is inactive by default, so no host firewall change is needed for SSH.
(The DMZ→LOCAL/INTERNAL exposure is governed by the VyOS zone firewall — Track N, N6.)

From the local machine — install your public key via the router jump-host:

```
$cmd = 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | ssh -J vyos@88.200.24.237 kyber@192.168.7.20 $cmd
```

Then test the connection:

```
ssh -J vyos@88.200.24.237 kyber@192.168.7.20
```