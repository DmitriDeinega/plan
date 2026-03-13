from datetime import datetime, timedelta, date
from zoneinfo import ZoneInfo

DATE_FORMAT = "%d%m%Y"


def format_date(dt: date) -> str:
    return dt.strftime(DATE_FORMAT)


def parse_date(s: str) -> date:
    return datetime.strptime(s, DATE_FORMAT).date()


def today(timezone_name: str) -> date:
    return datetime.now(ZoneInfo(timezone_name)).date()


def tomorrow(timezone_name: str) -> date:
    return today(timezone_name) + timedelta(days=1)


def yesterday(timezone_name: str) -> date:
    return today(timezone_name) - timedelta(days=1)


def next_day(d: date):
    return d + timedelta(days=1)


def prev_day(d: date):
    return d - timedelta(days=1)