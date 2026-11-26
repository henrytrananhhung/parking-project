package com.parking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Fields shared by every entity: a database-generated {@code id} (IDENTITY / BIGSERIAL columns) and a
 * {@code version} for optimistic locking.
 *
 * <p>Getters are deliberately not {@code final}: Hibernate proxies override {@link #getId()} so reading the id of
 * a lazy association does not load it.
 */
@MappedSuperclass
public abstract class AbstractEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }
}
