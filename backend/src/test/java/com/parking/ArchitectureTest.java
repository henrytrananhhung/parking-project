package com.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import com.parking.entity.AbstractEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/** Structural rules of the code base, checked with plain reflection and classpath scanning. */
class ArchitectureTest {

    @Test
    void everyEntityLivesInTheEntityPackageEndsWithEntityAndExtendsAbstractEntity() {
        List<Class<?>> entities = classesAnnotatedWith(Entity.class);

        assertThat(entities).isNotEmpty().allSatisfy(entity -> {
            assertThat(entity.getPackageName()).isEqualTo("com.parking.entity");
            assertThat(entity.getSimpleName()).endsWith("Entity");
            assertThat(AbstractEntity.class).isAssignableFrom(entity);
        });
    }

    @Test
    void abstractEntityHoldsTheIdAndTheOptimisticLockingVersion() throws NoSuchFieldException {
        assertThat(AbstractEntity.class.isAnnotationPresent(MappedSuperclass.class)).isTrue();
        Field id = AbstractEntity.class.getDeclaredField("id");
        Field version = AbstractEntity.class.getDeclaredField("version");

        assertThat(id.getType()).isEqualTo(Long.class);
        assertThat(id.isAnnotationPresent(Id.class)).isTrue();
        assertThat(version.getType()).isEqualTo(Long.class);
        assertThat(version.isAnnotationPresent(Version.class)).isTrue();
    }

    private static List<Class<?>> classesAnnotatedWith(Class<? extends java.lang.annotation.Annotation> annotation) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotation));
        return scanner.findCandidateComponents("com.parking").stream()
                .<Class<?>>map(bean -> ClassUtils.resolveClassName(bean.getBeanClassName(), null))
                .toList();
    }
}
