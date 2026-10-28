package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Local stub of {@code androidx.annotation.NonNull}.
 * The vendored Termux terminal engine (Apache-2.0) references this annotation, which is
 * compile-time only. We ship a minimal local stub instead of pulling the full AndroidX
 * annotation artifact into this Gradle-free build.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE,
        ElementType.TYPE, ElementType.TYPE_USE, ElementType.TYPE_PARAMETER, ElementType.CONSTRUCTOR,
        ElementType.PACKAGE, ElementType.ANNOTATION_TYPE})
public @interface NonNull {
}
