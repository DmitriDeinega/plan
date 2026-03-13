import random


def choice_times(arr, times):
    if times <= 0:
        return []
    if times > len(arr):
        raise Exception("Not enough foods available for selection")
    return random.sample(arr, times)