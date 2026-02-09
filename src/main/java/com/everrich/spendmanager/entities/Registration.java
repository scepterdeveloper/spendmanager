package com.everrich.spendmanager.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "REGISTRATION")
@NoArgsConstructor
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "registration_id_seq")
    @SequenceGenerator(name = "registration_id_seq", sequenceName = "registration_id_seq", initialValue = 100000, allocationSize = 1)
    private Long id;
    
    /**
     * The registration ID is a 6-digit number starting from 100000.
     * It is the same as the primary key (id) which uses a database sequence.
     * This is set automatically via @PrePersist callback.
     */
    @Column(name = "registration_id", unique = true)
    private String registrationId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegistrationStatus status;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    
    @OneToMany(mappedBy = "registration", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<AppUser> users = new ArrayList<>();
    
    public Registration(RegistrationStatus status) {
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }
    
    /**
     * Called before persist to set the registrationId.
     * Uses the sequence-generated id value formatted as a 6-digit string.
     */
    @PrePersist
    public void onPrePersist() {
        // The id is already assigned by the sequence before persist
        // because we're using GenerationType.SEQUENCE
        if (this.id != null && this.registrationId == null) {
            this.registrationId = formatRegistrationId(this.id);
        }
    }
    
    /**
     * Sets the registrationId based on the database-generated id.
     * This should be called after the entity is persisted if registrationId is still null.
     */
    public void generateRegistrationId() {
        if (this.id != null) {
            this.registrationId = formatRegistrationId(this.id);
        }
    }
    
    /**
     * Formats the sequence-generated id as a 6-digit string with leading zeros.
     * For example: 6 -> "000006", 123 -> "000123", 100000 -> "100000"
     */
    private String formatRegistrationId(Long id) {
        return String.format("%06d", id);
    }
    
    public void addUser(AppUser user) {
        users.add(user);
        user.setRegistration(this);
    }
    
    public void complete() {
        this.status = RegistrationStatus.COMPLETE;
        this.completedAt = LocalDateTime.now();
    }
    
    public boolean isOpen() {
        return this.status == RegistrationStatus.OPEN;
    }
}