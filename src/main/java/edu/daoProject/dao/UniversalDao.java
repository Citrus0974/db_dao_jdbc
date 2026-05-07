package edu.daoProject.dao;

import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


public class UniversalDao<T, ID> {

    @Autowired
    Connection connection;
    Class<T> clz;
    Class<ID> primaryKeyClass;
    Field primaryKey;
    Statement findAllReactiveStatement;

    public UniversalDao(Class<T> clz) {
        this.clz = clz;
        primaryKey = Arrays.stream(clz.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(PrimaryKey.class))
                .filter(f -> !f.getType().isPrimitive()).findAny()
                .orElseThrow(() -> new RuntimeException("valid primary key field not found"));
        primaryKeyClass = (Class<ID>) primaryKey.getType();
        try {
            findAllReactiveStatement = connection.createStatement();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<T> findAll() {
        List<T> result = new ArrayList<>();
        try {
            Statement statement = connection.createStatement();
            StringBuilder builder = getSelectQueryStringBuilder();
            ResultSet res = statement.executeQuery(builder.toString());
            while (res.next()) {
                result.add(createObject(res));
            }
            statement.close();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return result;
    }

    public List<T> findAllWithOffsetAndLimit(int offset, int limit) {
        List<T> result = new ArrayList<>();
        try {
            Statement statement = connection.createStatement();
            StringBuilder builder = getSelectQueryStringBuilder();
            builder.append(" ORDER BY ");
            builder.append(primaryKey.getName());
            builder.append(" ASC ");
            builder.append(" LIMIT ");
            builder.append(limit);
            builder.append(" OFFSET ");
            builder.append(offset);
            ResultSet res = statement.executeQuery(builder.toString());
            while (res.next()) {
                result.add(createObject(res));
            }
            statement.close();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return result;
    }

    public int count() {
        try {
            Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + clz.getSimpleName());
            int res = result.getInt(1);
            statement.close();
            return res;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Stream<T> findAllStream(int batchSize) {
        return StreamSupport.stream(findAll(batchSize).spliterator(), false);
    }

    public Iterable<T> findAll(int batchSize) {
        return new DaoIterable<>(batchSize, this);
    }

    public Stream<T> findAllReactive(int batchSize) {
        List<T> result = new ArrayList<>();
        try {
            Statement statement = connection.createStatement();
            statement.setFetchSize(batchSize);
            StringBuilder stringBuilder = getSelectQueryStringBuilder();
            ResultSet res = statement.executeQuery(stringBuilder.toString());
            if(!res.next()) return  Stream.empty();
            Stream<T> iterate = Stream.iterate(
                    (T) createObject(res),
                    x-> {
                        try {
                            return res.next();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    },
                    (x) -> (T)createObject(res)
            );

            return iterate;
        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private StringBuilder getSelectQueryStringBuilder() {
        StringBuilder builder = new StringBuilder("SELECT ");
        Field[] fields = clz.getDeclaredFields();
        for (int i = 0; i < fields.length - 1; i++) {
            builder.append(fields[i].getName());
            builder.append(", ");
        }
        builder.append(fields[fields.length - 1].getName());
        builder.append(" FROM ");
        builder.append(clz.getSimpleName());
        return builder;
    }

    private T createObject(ResultSet resultSet) {
        try {
            Field[] fields = clz.getDeclaredFields();
            Constructor<?> constructor = clz.getDeclaredConstructor();
            Object obj = constructor.newInstance();
            for (Field f : fields) {
                f.setAccessible(true);
                Class<?> fieldClass = f.getType();
                String fieldName = f.getName();
                Object fieldValue = resultSet.getObject(fieldName, fieldClass);
                f.set(obj, fieldValue);
            }
            return (T) obj;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<T> findByID(ID id) {
        List<T> res = new ArrayList<>();
        try {
            StringBuilder builder = getSelectQueryStringBuilder();
            builder.append(" WHERE ");
            builder.append(primaryKey.getName());
            builder.append(" = ?");
            PreparedStatement statement = connection.prepareStatement(builder.toString());
            statement.setObject(1, id);
            ResultSet result = statement.executeQuery();
            while (result.next()) {
                res.add(createObject(result));
            }

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return res;
    }

    public boolean insertOne(T obj) {
        StringBuilder query = new StringBuilder("INSERT INTO ");
        query.append(clz.getSimpleName());
        query.append(" (");
        Field[] fields = clz.getDeclaredFields();
        int valuesCount = 0;
        //какие поля (имена столбцов) инсёртить - все, кроме первичного ключа
        for (int i = 0; i < fields.length - 1; i++) {
            if (!fields[i].equals(primaryKey)) {
                query.append(fields[i].getName());
                query.append(", ");
                valuesCount++;
            }
        }
        if (!fields[fields.length - 1].equals(primaryKey)) {
            query.append(fields[fields.length - 1].getName());
            valuesCount++;
        } else {
            query.deleteCharAt(query.length() - 1);
        }
        query.append(") VALUES (");
        //Сколько полей надо будет подставить
        for (int i = 0; i < valuesCount - 1; i++) {
            query.append("?, ");
        }
        query.append("?)");

        //Создание и исполнение запроса вставки. После него - запрос select для получения нового id, с попыткой избежать грязное чтение
        try {
            int initialTransactionIsolation = connection.getTransactionIsolation();
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            PreparedStatement preparedStatement = connection.prepareStatement(query.toString());
            int skippedIDs = 0;
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].equals(primaryKey)) {
                    skippedIDs++;
                } else {
                    fields[i].setAccessible(true);
                    preparedStatement.setObject(i - skippedIDs + 1, fields[i].get(obj));
                }
            }
            int inserted = preparedStatement.executeUpdate();
            if (inserted == 0) return false; //если вдруг не вставилось, но исключения не было
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT " + primaryKey.getName() + " FROM " + clz.getSimpleName() + " ORDER BY " + primaryKey.getName() + " DESC LIMIT 1");
            Object primaryKeyValue = null;
            while (resultSet.next()) {
                primaryKeyValue = resultSet.getObject(1, primaryKeyClass);
            }
            primaryKey.setAccessible(true);
            primaryKey.set(obj, primaryKeyValue); //Поставить значение первичного ключа в объекте так, чтобы оно совпадало с базой
            connection.commit();
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(initialTransactionIsolation);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int insertMany(T... objects) {
        StringBuilder query = new StringBuilder("INSERT INTO ");
        query.append(clz.getSimpleName());
        query.append(" (");
        Field[] fields = clz.getDeclaredFields();
        int valuesCount = 0;
        //какие поля (имена столбцов) инсёртить - все, кроме первичного ключа
        for (int i = 0; i < fields.length - 1; i++) {
            if (!fields[i].equals(primaryKey)) {
                query.append(fields[i].getName());
                query.append(", ");
                valuesCount++;
            }
        }
        if (!fields[fields.length - 1].equals(primaryKey)) {
            query.append(fields[fields.length - 1].getName());
            valuesCount++;
        } else {
            query.deleteCharAt(query.length() - 1);
        }
        query.append(") VALUES ");
        //Сколько полей надо будет подставить
        for (T obj : objects) {
            query.append("(");
            for (int i = 0; i < valuesCount - 1; i++) {
                query.append("?, ");
            }
            query.append("?),");
        }
        query.deleteCharAt(query.length() - 1);
        //Создание и исполнение запроса вставки
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(query.toString(), Statement.RETURN_GENERATED_KEYS);
            int skippedIDs = 0;
            for (int j = 0; j < objects.length; j++) {
                skippedIDs++;
                for (int i = 0; i < fields.length; i++) {
//                    if (fields[i].equals(primaryKey))
                    if (!fields[i].equals(primaryKey)) {
                        fields[i].setAccessible(true);
                        preparedStatement.setObject(j * fields.length + i - skippedIDs + 1, fields[i].get(objects[j]));
                    }
                }
            }
            int inserted = preparedStatement.executeUpdate();
            if (inserted != objects.length) {
                connection.rollback();
                return 0;
            }
            ; //если вдруг не вставилось, но исключения не было
            ResultSet ids = preparedStatement.getGeneratedKeys();
            Object primaryKeyValue = null;
            int rowNum = 0;
            while (ids.next()) {
                primaryKeyValue = ids.getObject(1, primaryKeyClass);
                primaryKey.setAccessible(true);
                primaryKey.set(objects[rowNum], primaryKeyValue); //Поставить значение первичного ключа в объекте так, чтобы оно совпадало с базой
                connection.commit();
                rowNum++;
            }
            return inserted;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean updateOne(T obj) {
        StringBuilder query = new StringBuilder("UPDATE ");
        query.append(clz.getSimpleName());
        query.append(" SET ");
        Field[] fields = clz.getDeclaredFields();
        for (Field f : fields) {
            if (!f.equals(primaryKey)) {
                query.append(f.getName());
                query.append(" = ?, ");
            }
        }
        query.deleteCharAt(query.length() - 2);
        query.append(" WHERE ");
        query.append(primaryKey.getName());
        query.append(" = ?");
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(query.toString());
            int skippedIDs = 0;
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].equals(primaryKey)) {
                    skippedIDs++;
                } else {
                    fields[i].setAccessible(true);
                    preparedStatement.setObject(i - skippedIDs + 1, fields[i].get(obj));
                }
            }
            primaryKey.setAccessible(true);
            preparedStatement.setObject(fields.length, primaryKey.get(obj));
            int updated = preparedStatement.executeUpdate();
            if (updated == 0) {
                connection.rollback();
                return false;
            }
            ;
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int delete(T obj) {
        try {
            StringBuilder builder = new StringBuilder("DELETE FROM ");
            builder.append(clz.getSimpleName());
            builder.append(" WHERE ");
            builder.append(primaryKey.getName());
            builder.append(" = ");
            primaryKey.setAccessible(true);
            builder.append(primaryKey.get(obj));
            Statement statement = connection.createStatement();
            int deleted = statement.executeUpdate(builder.toString());
            if (deleted > 0) {
                primaryKey.set(obj, null);
            }
            return deleted;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    //добавить инсёрт+ и апдейт+, дочинить
    //пагинация - стрим, итерируется по заданному числу (батч) данных и при выходе за пределы вызывает следующий запрос (в find all)
    public int executeCustomQuery(String query, List<T> result) {
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(query);
            boolean isResultSet = preparedStatement.execute();
            if (!isResultSet) {
                return preparedStatement.getUpdateCount();
            } else {
                ResultSet resSet = preparedStatement.getResultSet();
                int rowCount = 0;
                while (resSet.next()) {
                    result.add(createObject(resSet));
                    rowCount++;
                }
                return rowCount;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}
