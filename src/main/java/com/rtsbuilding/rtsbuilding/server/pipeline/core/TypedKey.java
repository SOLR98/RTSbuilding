package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import java.util.Objects;

/**
 * A key that carries its expected value type at both compile time and
 * runtime.
 *
 * <p>Use with {@link PipelineContext#getArg(TypedKey)} /
 * {@link PipelineContext#getData(TypedKey)} to obtain type-safe access to
 * pipeline context arguments and shared data.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * public static final TypedKey<Integer> KEY_WORKFLOW_ENTRY_ID =
 *         new TypedKey<>("workflowEntryId", Integer.class);
 *
 * int id = ctx.getData(KEY_WORKFLOW_ENTRY_ID);  // no unchecked cast
 * }</pre>
 *
 * @param <T> the expected value type
 */
public final class TypedKey<T> {

    private final String name;
    private final Class<T> type;

    public TypedKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Returns the underlying map key string. */
    public String name() {
        return name;
    }

    /** Returns the expected value type for runtime checked casts. */
    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return name + "<" + type.getSimpleName() + ">";
    }
}
