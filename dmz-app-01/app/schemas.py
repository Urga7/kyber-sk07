from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict


class CustomerIn(BaseModel):
    name: str
    email: str


class CustomerOut(CustomerIn):
    model_config = ConfigDict(from_attributes=True)
    id: int
    created_at: datetime


class OrderIn(BaseModel):
    customer_id: int
    product: str
    quantity: int
    amount: Decimal


class OrderOut(OrderIn):
    model_config = ConfigDict(from_attributes=True)
    id: int
    created_at: datetime
