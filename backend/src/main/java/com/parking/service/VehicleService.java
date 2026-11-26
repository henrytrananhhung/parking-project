package com.parking.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.parking.dto.response.VehicleResponse;
import com.parking.entity.VehicleEntity;
import com.parking.exception.BusinessException;
import com.parking.exception.Constraints;
import com.parking.exception.ErrorCode;
import com.parking.mapper.VehicleMapper;
import com.parking.repository.UserRepository;
import com.parking.repository.VehicleRepository;

@Service
public class VehicleService {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-]");
    private static final Pattern VALID_PLATE = Pattern.compile("^[A-Z0-9]{4,12}$");

    private final VehicleRepository vehicles;
    private final UserRepository users;

    public VehicleService(VehicleRepository vehicles, UserRepository users) {
        this.vehicles = vehicles;
        this.users = users;
    }

    @Transactional
    public VehicleResponse register(long userId, String rawPlate) {
        String plate = normalizePlate(rawPlate);
        if (vehicles.existsByPlate(plate)) {
            throw new BusinessException(ErrorCode.PLATE_TAKEN);
        }
        try {
            // getReferenceById: the owner is only needed as a foreign key, so no SELECT.
            VehicleEntity vehicle = new VehicleEntity(users.getReferenceById(userId), plate);
            return VehicleMapper.toResponse(vehicles.saveAndFlush(vehicle));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, VehicleEntity.UK_PLATE)) {
                throw new BusinessException(ErrorCode.PLATE_TAKEN);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> list(long userId) {
        return vehicles.findByUserIdOrderById(userId).stream().map(VehicleMapper::toResponse).toList();
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
