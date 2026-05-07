package edu.daoProject.dao;


import edu.daoProject.model.Employee;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@Configuration
public class DaoConfig {
    @Bean
    public Connection connection(){
        try {
            return DriverManager.getConnection("jdbc:h2:file:./h2db");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Bean("EmployeeDao")
    @Qualifier("EmployeeDao")
    public UniversalDao<Employee, Integer> EmployeeDao(){
        return new UniversalDao<>(Employee.class);
    }
}
