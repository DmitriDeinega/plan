from datetime import datetime
from app.core.config import DATE_FORMAT


def format_date(dt: datetime) -> str:
    return dt.strftime(DATE_FORMAT)


def parse_date(s: str) -> datetime:
    return datetime.strptime(s, DATE_FORMAT)