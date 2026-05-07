package edu.daoProject.model;

import edu.daoProject.dao.PrimaryKey;

public class Employee {
    @PrimaryKey
    private Integer id = 0;
    private String name;
    private Integer department;

    @Override
    public String toString() {
        return "Employee{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", department=" + department +
                '}';
    }

    public Employee(String name, Integer department) {
        this.name = name;
        this.department = department;
    }

    public Employee() {
    }

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getDepartment() {
        return department;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDepartment(Integer department) {
        this.department = department;
    }
}
