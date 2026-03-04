from pydantic import BaseModel
from app.core.enums import Status


class BaseResponse(BaseModel):
    status: Status = Status.SUCCESS
    errorMessage: str = ""