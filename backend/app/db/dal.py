from pymongo import MongoClient


class DAL:
    def __init__(self, mongo_uri: str, mongo_db: str):
        self.client = MongoClient(mongo_uri)
        self.db = self.client[mongo_db]

    def find_one(self, collection, key, columns):
        return self.db[collection].find_one(key, columns)

    def find_all(self, collection, key, columns, sort=None):
        find = self.db[collection].find(key, columns)
        if sort is not None:
            find = find.sort(sort)
        return list(find)

    def update_one(self, collection, key, data):
        return self.db[collection].update_one(key, data)

    def insert_one(self, collection, document):
        return self.db[collection].insert_one(document)

    def remove_all(self, collection):
        return self.db[collection].delete_many({})

    def insert_many(self, collection, documents):
        return self.db[collection].insert_many(documents)

    def delete_one(self, collection, key):
        return self.db[collection].delete_one(key)