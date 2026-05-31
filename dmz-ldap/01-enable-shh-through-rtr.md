```
sudo dnf -y install openssh-server
sudo systemctl enable --now sshd
```

firewalld permits the `ssh` service in the default zone out of the box, so no
firewall change is needed for this step.

From local machine:
```
$cmd = 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | ssh -J vyos@10.7.99.1 kyber@192.168.7.30 $cmd
```

Then test the ssh connection:
```
ssh -J vyos@10.7.99.1 kyber@192.168.7.30
```