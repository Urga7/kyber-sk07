# Real team users (VPN accounts)

Create actual users for each team member with which they will connect to the VPN.

```
ipa user-add luka --first=Luka --last=Mikić --random
ipa user-add urban --first=Urban --last=Gajšek --random
ipa group-add-member vpn-users --users=luka --users=urban
```