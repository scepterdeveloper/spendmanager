package com.everrich.spendmanager.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
// import lombok.AllArgsConstructor; // Can be removed since we add a custom constructor

@Getter
@Setter
@Entity
@Table(name = "CATEGORY")
@NoArgsConstructor // Ensure the public no-arg constructor is generated
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    
    private String description;
    
    // 🟢 FIX: Explicitly define the constructor required by CategoryService
    public Category(String name, String description) {
        this.name = name;
        this.description = description;
    }
}