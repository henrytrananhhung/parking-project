package com.parking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.parking.dto.response.VehicleResponse;
import com.parking.entity.VehicleEntity;
import com.parking.exception.BusinessException;
import com.parking.exception.ErrorCode;
import com.parking.repository.VehicleRepository;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock
    VehicleRepository vehicles;
    @InjectMocks
    VehicleService service;

    @Test
    void normalizesPlateToUpperCaseLettersAndDigits() {
        assertThat(VehicleService.normalizePlate(" 51f-123.45 ")).isEqualTo("51F12345");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"AB1", "ABCDEFGHIJKLM", "51F_123", "51F#12345"})
    void rejectsInvalidPlates(String plate) {
        assertThatThrownBy(() -> service.register(1L, plate))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.INVALID_PLATE);
        verify(vehicles, never()).saveAndFlush(any());
    }

    @Test
    void registersNormalizedPlateForUser() {
        when(vehicles.existsByPlate("51F12345")).thenReturn(false);
        when(vehicles.saveAndFlush(any(VehicleEntity.class))).thenAnswer(inv -> withId(inv.getArgument(0), 31L));

        VehicleResponse response = service.register(5L, "51F-123.45");

        assertThat(response).isEqualTo(new VehicleResponse(31L, "51F12345"));
        ArgumentCaptor<VehicleEntity> saved = ArgumentCaptor.forClass(VehicleEntity.class);
        verify(vehicles).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(5L);
        assertThat(saved.getValue().getPlate()).isEqualTo("51F12345");
    }

    @Test
    void rejectsPlateAlreadyRegistered() {
        when(vehicles.existsByPlate("51F12345")).thenReturn(true);

        assertThatThrownBy(() -> service.register(5L, "51F12345"))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.PLATE_TAKEN);
        verify(vehicles, never()).saveAndFlush(any());
    }

    @Test
    void mapsConcurrentDuplicatePlateToPlateTaken() {
        when(vehicles.existsByPlate("51F12345")).thenReturn(false);
        when(vehicles.saveAndFlush(any(VehicleEntity.class))).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_vehicles_plate\""));

        assertThatThrownBy(() -> service.register(5L, "51F12345"))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.PLATE_TAKEN);
    }

    private static VehicleEntity withId(VehicleEntity vehicle, long id) {
        ReflectionTestUtils.setField(vehicle, "id", id);
        return vehicle;
    }
}
