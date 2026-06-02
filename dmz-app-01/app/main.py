from fastapi import Depends, FastAPI, HTTPException, Request
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from . import models, schemas
from .auth import require_writer
from .database import Base, engine, get_db
from .serialization import negotiate

Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="kyber REST API",
    version="1.0",
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json"
)


def dump(obj, schema):
    return schema.model_validate(obj).model_dump()


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/")
def read_root():
    return {
        "message": "Welcome to the kyber REST API",
        "version": "1.0",
        "documentation": "/docs",
        "status": "reachable"
    }


@app.get("/customers")
def list_customers(request: Request, db: Session = Depends(get_db)):
    rows = [dump(c, schemas.CustomerOut) for c in db.query(models.Customer).all()]
    return negotiate(request, rows, root="customers", item="customer")


@app.get("/customers/{cid}")
def get_customer(cid: int, request: Request, db: Session = Depends(get_db)):
    c = db.get(models.Customer, cid)
    if not c:
        raise HTTPException(404, "customer not found")
    return negotiate(request, dump(c, schemas.CustomerOut), root="customers", item="customer")


@app.post("/customers", dependencies=[Depends(require_writer)])
def create_customer(
    payload: schemas.CustomerIn,
    request: Request,
    db: Session = Depends(get_db)
):
    c = models.Customer(**payload.model_dump())
    db.add(c)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "email already exists")
    db.refresh(c)
    return negotiate(
        request, dump(c, schemas.CustomerOut), root="customers", item="customer", status_code=201
    )


@app.put("/customers/{cid}", dependencies=[Depends(require_writer)])
def update_customer(
    cid: int,
    payload: schemas.CustomerIn,
    request: Request,
    db: Session = Depends(get_db)
):
    c = db.get(models.Customer, cid)
    if not c:
        raise HTTPException(404, "customer not found")
    c.name = payload.name
    c.email = payload.email
    db.commit()
    db.refresh(c)
    return negotiate(request, dump(c, schemas.CustomerOut), root="customers", item="customer")


@app.delete("/customers/{cid}", status_code=204, dependencies=[Depends(require_writer)])
def delete_customer(
    cid: int, db: Session = Depends(get_db)
):
    c = db.get(models.Customer, cid)
    if not c:
        raise HTTPException(404, "customer not found")
    db.delete(c)
    db.commit()


@app.get("/orders")
def list_orders(request: Request, db: Session = Depends(get_db)):
    rows = [dump(o, schemas.OrderOut) for o in db.query(models.Order).all()]
    return negotiate(request, rows, root="orders", item="order")


@app.get("/orders/{oid}")
def get_order(oid: int, request: Request, db: Session = Depends(get_db)):
    o = db.get(models.Order, oid)
    if not o:
        raise HTTPException(404, "order not found")
    return negotiate(request, dump(o, schemas.OrderOut), root="orders", item="order")


@app.post("/orders", dependencies=[Depends(require_writer)])
def create_order(
    payload: schemas.OrderIn,
    request: Request,
    db: Session = Depends(get_db)
):
    if not db.get(models.Customer, payload.customer_id):
        raise HTTPException(400, "customer_id does not exist")
    o = models.Order(**payload.model_dump())
    db.add(o)
    db.commit()
    db.refresh(o)
    return negotiate(
        request, dump(o, schemas.OrderOut), root="orders", item="order", status_code=201
    )


@app.put("/orders/{oid}", dependencies=[Depends(require_writer)])
def update_order(
    oid: int,
    payload: schemas.OrderIn,
    request: Request,
    db: Session = Depends(get_db)
):
    o = db.get(models.Order, oid)
    if not o:
        raise HTTPException(404, "order not found")
    for field, value in payload.model_dump().items():
        setattr(o, field, value)
    db.commit()
    db.refresh(o)
    return negotiate(request, dump(o, schemas.OrderOut), root="orders", item="order")


@app.delete("/orders/{oid}", status_code=204, dependencies=[Depends(require_writer)])
def delete_order(oid: int, db: Session = Depends(get_db)):
    o = db.get(models.Order, oid)
    if not o:
        raise HTTPException(404, "order not found")
    db.delete(o)
    db.commit()
