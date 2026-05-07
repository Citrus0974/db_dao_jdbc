package edu.daoProject.dao;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DaoIterable<T> implements Iterable<T> {
    int batchSize;
    UniversalDao<T, ?> universalDao;

    public DaoIterable(int batchSize, UniversalDao<T, ?> universalDao) {
        this.batchSize = batchSize;
        this.universalDao = universalDao;
    }

    @Override
    public Iterator<T> iterator() {
        return new DaoIterator<>();
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    private class DaoIterator<V> implements Iterator<V> {
        int currentIndex;
        int fetchIndex;
        List<V> fetched = new ArrayList<>();

        public DaoIterator() {
            currentIndex = 0;
            fetchIndex = 0;
//            for(int i=0; i<DaoIterable.this.batchSize; i++){}
            //запрос на получения первых batchSize записей и добавление их в список
//            getNextBatch(0, batchSize); //или даже не делать запрос если у нас lazy
            fetchIndex = batchSize;
        }

        @Override
        public boolean hasNext() {
            if(currentIndex<fetchIndex) return true;
            return DaoIterable.this.universalDao.count() > currentIndex;
//            return DaoIterable.this.universalDao.findAll().size() > currentIndex;
            //но вообще нужен метод который будет возвращать число строк в таблице, вынимать все очень тупо
        }

        @Override
        public V next() {
            V obj;
            if(currentIndex<fetchIndex){
                obj = fetched.get(currentIndex);
            } else {
                fetched.addAll(getNextBatch(fetchIndex, batchSize));
                fetchIndex+=batchSize;
                obj = fetched.get(currentIndex);
            }
            currentIndex++;
            return obj;
        }

        private List<V>  getNextBatch(int offset, int limit){
            //Вызов метода в дао, который делал бы select с offset и limit

            return (List<V>) DaoIterable.this.universalDao.findAllWithOffsetAndLimit(offset, limit);
        }
    }
}
