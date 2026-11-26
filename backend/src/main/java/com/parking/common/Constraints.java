package com.parking.common;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;

/** Identifies which named database constraint caused a DataIntegrityViolationException. */
public final class Constraints {

    private Constraints() {
    }

    public static boolean violated(DataIntegrityViolationException ex, String constraintName) {
        String name = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase(Locale.ROOT).contains(name)) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().toLowerCase(Locale.ROOT).contains(name)) {
                return true;
            }
        }
        return false;
    }
}
