package com.parking.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.parking.dto.response.SpaceStatusResponse;
import com.parking.repository.projection.SpaceOccupancy;

class ReservationMapperTest {

    @Test
    void plateSuffixIsLastThreeCharacters() {
        assertThat(ReservationMapper.plateSuffix("51F12345")).isEqualTo("345");
        assertThat(ReservationMapper.plateSuffix("AB")).isEqualTo("AB");
    }

    @Test
    void reservedSpaceShowsOnlyThePlateSuffixAndWhetherItIsMine() {
        SpaceOccupancy row = new SpaceOccupancy(7L, "51F12345", 3L);

        assertThat(ReservationMapper.toSpaceStatus(row, 3L)).isEqualTo(new SpaceStatusResponse(7, true, "345", true));
        assertThat(ReservationMapper.toSpaceStatus(row, 4L)).isEqualTo(new SpaceStatusResponse(7, true, "345", false));
    }

    @Test
    void freeSpaceHasNoPlateAndIsNobodys() {
        assertThat(ReservationMapper.toSpaceStatus(new SpaceOccupancy(8L, null, null), 3L))
                .isEqualTo(new SpaceStatusResponse(8, false, null, false));
    }
}
