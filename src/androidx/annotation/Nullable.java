package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Local stub of {@code androidx.annotation.Nullable}.
 * Compile-time only; see {@link NonNull} for rationale.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE,
        ElementType.TYPE, ElementType.TYPE_USE, ElementType.TYPE_PARAMETER, ElementType.CONSTRUCTOR,
        ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
public @interface Nullable {
}
