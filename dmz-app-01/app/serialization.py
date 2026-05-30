from datetime import datetime
from decimal import Decimal
from xml.sax.saxutils import escape

from fastapi import Request, Response
from fastapi.responses import JSONResponse


def _jsonable(value):
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, list):
        return [_jsonable(v) for v in value]
    if isinstance(value, dict):
        return {k: _jsonable(v) for k, v in value.items()}
    return value


def _fields_xml(record):
    return "".join(f"<{k}>{escape(str(v))}</{k}>" for k, v in record.items())


def _to_xml(data, root, item):
    head = '<?xml version="1.0" encoding="UTF-8"?>'
    if isinstance(data, list):
        body = "".join(f"<{item}>{_fields_xml(r)}</{item}>" for r in data)
        return f"{head}<{root}>{body}</{root}>"
    return f"{head}<{item}>{_fields_xml(data)}</{item}>"


def _to_html(data, root, item):
    rows = data if isinstance(data, list) else [data]
    if not rows:
        return f"<!doctype html><html><body><h1>{root}</h1><p>No records.</p></body></html>"
    cols = list(rows[0].keys())
    head = "".join(f"<th>{escape(c)}</th>" for c in cols)
    body = ""
    for r in rows:
        cells = "".join(f"<td>{escape(str(r.get(c, '')))}</td>" for c in cols)
        body += f"<tr>{cells}</tr>"
    return (
        "<!doctype html><html><head><meta charset='utf-8'>"
        f"<title>{root}</title></head><body><h1>{root}</h1>"
        "<table border='1' cellpadding='4'>"
        f"<thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"
        "</body></html>"
    )


def negotiate(request: Request, data, *, root, item, status_code=200):
    accept = (request.headers.get("accept") or "").lower()
    if "application/xml" in accept or "text/xml" in accept:
        return Response(
            _to_xml(data, root, item),
            media_type="application/xml",
            status_code=status_code,
        )
    if "text/html" in accept:
        return Response(
            _to_html(data, root, item),
            media_type="text/html",
            status_code=status_code,
        )
    return JSONResponse(_jsonable(data), status_code=status_code)
