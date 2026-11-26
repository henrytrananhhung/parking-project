package com.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

import com.parking.entity.AbstractEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/** Structural rules of the code base, checked with plain reflection, classpath scanning and a source scan. */
class ArchitectureTest {

    private static final Pattern DIRECT_JDBC = Pattern.compile("\\b(java\\.sql|javax\\.sql|org\\.springframework\\.jdbc)\\.");

    @Test
    void everyEntityLivesInTheEntityPackageEndsWithEntityAndExtendsAbstractEntity() {
        List<Class<?>> entities = classes(new AnnotationTypeFilter(Entity.class), "com.parking");

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

    @Test
    void applicationCodeDoesNotUseJdbcDirectly() throws IOException {
        Path sources = Path.of("src/main/java");
        assertThat(sources).isDirectory();

        try (Stream<Path> files = Files.walk(sources)) {
            List<Path> offenders = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> DIRECT_JDBC.matcher(read(file)).find())
                    .toList();
            assertThat(offenders).as("java.sql / javax.sql / Spring JDBC in application code").isEmpty();
        }
    }

    @Test
    void controllersNeitherReturnNorAcceptEntities() {
        List<Class<?>> controllers = classes(new AnnotationTypeFilter(RestController.class), "com.parking");

        assertThat(controllers).isNotEmpty().allSatisfy(controller -> {
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                assertThat(mentionsEntity(method.getGenericReturnType())).as("%s return type", method).isFalse();
                assertThat(Arrays.stream(method.getGenericParameterTypes()).anyMatch(ArchitectureTest::mentionsEntity))
                        .as("%s parameters", method).isFalse();
            }
        });
    }

    @Test
    void dtosDoNotContainEntities() {
        List<Class<?>> dtos = classes(new RegexPatternTypeFilter(Pattern.compile(".*")), "com.parking.dto");

        assertThat(dtos).isNotEmpty().allSatisfy(dto -> {
            assertThat(dto.isRecord()).as("%s is a record", dto).isTrue();
            for (RecordComponent component : dto.getRecordComponents()) {
                assertThat(mentionsEntity(component.getGenericType())).as("%s.%s", dto, component.getName()).isFalse();
            }
        });
    }

    private static boolean mentionsEntity(Type type) {
        return switch (type) {
            case Class<?> c -> AbstractEntity.class.isAssignableFrom(c) || (c.isArray() && mentionsEntity(c.getComponentType()));
            case ParameterizedType p -> mentionsEntity(p.getRawType())
                    || Arrays.stream(p.getActualTypeArguments()).anyMatch(ArchitectureTest::mentionsEntity);
            case WildcardType w -> Arrays.stream(w.getUpperBounds()).anyMatch(ArchitectureTest::mentionsEntity);
            case GenericArrayType g -> mentionsEntity(g.getGenericComponentType());
            default -> false;
        };
    }

    private static List<Class<?>> classes(TypeFilter filter, String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(filter);
        return scanner.findCandidateComponents(basePackage).stream()
                .<Class<?>>map(bean -> ClassUtils.resolveClassName(bean.getBeanClassName(), null))
                .toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
