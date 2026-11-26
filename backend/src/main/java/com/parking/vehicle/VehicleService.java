package com.parking.vehicle;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.parking.common.BusinessException;
import com.parking.common.Constraints;
import com.parking.common.ErrorCode;

@Service
public class VehicleService {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-]");
    private static final Pattern VALID_PLATE = Pattern.compile("^[A-Z0-9]{4,12}$");

    private final VehicleRepository vehicles;

    public VehicleService(VehicleRepository vehicles) {
        this.vehicles = vehicles;
    }

    @Transactional
    public Vehicle register(long userId, String rawPlate) {
        String plate = normalizePlate(rawPlate);
        if (vehicles.existsByPlate(plate)) {
            throw new BusinessException(ErrorCode.PLATE_TAKEN);
        }
        try {
            return vehicles.saveAndFlush(new Vehicle(userId, plate));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, "uk_vehicles_plate")) {
                throw new BusinessException(ErrorCode.PLATE_TAKEN);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<Vehicle> list(long userId) {
        return vehicles.findByUserIdOrderById(userId);
    }

    /** "51f-123.45" -> "51F12345". */
    public static String normalizePlate(String raw) {
        String plate = raw == null ? "" : SEPARATORS.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        if (!VALID_PLATE.matcher(plate).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PLATE);
        }
        return plate;
    }
}
