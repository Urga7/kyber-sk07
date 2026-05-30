import os
import ssl

from fastapi import Depends, HTTPException
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from ldap3 import ALL, SUBTREE, Connection, Server, Tls

LDAP_URI = os.environ.get("KYBER_LDAP_URI", "ldaps://kyber-ldap.kyber.local")
LDAP_BASE = os.environ.get("KYBER_LDAP_BASE", "dc=kyber,dc=local")
WRITER_GROUP = os.environ.get("KYBER_WRITER_GROUP", "api-writers")
AUTH_ENABLED = os.environ.get("KYBER_AUTH_ENABLED", "1") == "1"

security = HTTPBasic(auto_error=AUTH_ENABLED)
_UNAUTH = {"WWW-Authenticate": "Basic"}


def require_writer(credentials: HTTPBasicCredentials = Depends(security)):
    if not AUTH_ENABLED:
        return "auth-disabled"

    user_dn = f"uid={credentials.username},cn=users,cn=accounts,{LDAP_BASE}"
    tls = Tls(validate=ssl.CERT_REQUIRED)  # trusts the IPA CA via the system store
    server = Server(LDAP_URI, use_ssl=True, tls=tls, get_info=ALL)
    try:
        conn = Connection(
            server, user=user_dn, password=credentials.password, auto_bind=True
        )
    except Exception:
        raise HTTPException(401, "Invalid credentials", headers=_UNAUTH)

    conn.search(user_dn, "(objectClass=*)", search_scope=SUBTREE, attributes=["memberOf"])
    groups = []
    if conn.entries and "memberOf" in conn.entries[0]:
        groups = [str(v).lower() for v in conn.entries[0]["memberOf"].values]
    conn.unbind()

    if not any(g.startswith(f"cn={WRITER_GROUP.lower()},") for g in groups):
        raise HTTPException(403, f"User is not a member of {WRITER_GROUP}")
    return credentials.username
