package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Local stub of {@code androidx.annotation.RequiresApi}.
 * Compile-time only; see {@link NonNull} for rationale. The {@code api()} alias mirrors the
 * upstream annotation that callers use as {@code @RequiresApi(api = Build.VERSION_CODES.O)}.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE, ElementType.FIELD,
        ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
public @interface RequiresApi {
    int value() default 0;
    int api() default 0;
}
