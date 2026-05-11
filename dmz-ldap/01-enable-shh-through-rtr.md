sudo apt -y install openssh-server
sudo systemctl enable --now ssh

From local machine:
```
$cmd = 'mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | ssh -J vyos@88.200.24.237 kyber@192.168.7.30 $cmd
```

Then test the ssh connection:
```
ssh -J vyos@88.200.24.237 kyber@192.168.7.30
```