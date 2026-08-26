package io.realmledger.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Links a test to the acceptance criterion it proves.
 *
 * <p>This is the hook the spec gate reads. {@code tools/spec_gate.py} parses every
 * criterion id out of {@code specs/} and every {@code @SpecRef} out of the test sources,
 * then fails the build when an implemented spec has a criterion no test claims, or when a
 * test points at a criterion that does not exist.
 *
 * <p>It exists because "the tests pass" and "the spec is satisfied" are different
 * statements, and an agent optimises for the first one. Requiring the link makes the gap
 * visible to a machine instead of to a reviewer's memory.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(SpecRefs.class)
public @interface SpecRef {

    /** Criterion id, for example {@code WD-4}. */
    String value();
}
