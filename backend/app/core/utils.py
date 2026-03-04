import random
from datetime import datetime, timedelta


def today():
    return datetime.today()


def tomorrow():
    return datetime.today() + timedelta(days=1)


def yesterday():
    return datetime.today() - timedelta(days=1)


def choice_times(arr, times):
    if times <= 0:
        return []
    if times > len(arr):
        raise Exception("Not enough foods available for selection")
    return random.sample(arr, times)


def next_day(d):
    return d + timedelta(days=1)


def prev_day(d):
    return d - timedelta(days=1)