import logging

from app.core.request_context import request_id_var


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get()
        return True


def setup_logging(*, level: str) -> logging.Logger:
    logger = logging.getLogger("plan")
    logger.setLevel(level)

    # prevent duplicate handlers on reload
    if logger.handlers:
        return logger

    fmt = "%(asctime)s %(levelname)s %(name)s rid=%(request_id)s %(message)s"
    datefmt = "%Y-%m-%d %H:%M:%S"
    formatter = logging.Formatter(fmt=fmt, datefmt=datefmt)

    rid_filter = RequestIdFilter()

    console = logging.StreamHandler()
    console.setFormatter(formatter)
    console.addFilter(rid_filter)
    logger.addHandler(console)

    return logger