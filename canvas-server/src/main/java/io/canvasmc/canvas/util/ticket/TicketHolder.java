package io.canvasmc.canvas.util.ticket;

import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Holder of a ticket, which provides utilities for submitting actions to the holder object.
 *
 * @param <T>
 *     the ticket type, must be a record
 */
public class TicketHolder<T extends Record> {
    private final java.util.concurrent.atomic.AtomicReference<T> ticket = new java.util.concurrent.atomic.AtomicReference<>(); // Rolia - atomic propagate/pop

    /**
     * Fetches the current ticket
     *
     * @return the active ticket
     */
    @Nullable
    public T get() {
        return this.ticket.get();
    }

    /**
     * Fetches and clears the current ticket
     *
     * @return the active ticket
     */
    @Nullable
    public T pop() {
        return this.ticket.getAndSet(null);
    }

    /**
     * Fetches and clears the current ticket if present, throws otherwise
     *
     * @return the active ticket
     *
     * @throws IllegalStateException
     *     if not present
     */
    @NonNull
    public T popOrThrow() {
        T popped = pop();
        if (popped == null) throw new IllegalStateException("Not propagated");
        return popped;
    }

    /**
     * Consumes the active ticket if contained, removing the current ticket and processing it with the
     * {@link Consumer<T>} provided
     *
     * @param ifContained
     *     the consumer
     */
    public void consumeIfPresent(final @NonNull Consumer<T> ifContained) {
        // fetch and remove, if not null consume ticket
        final T popped = pop();
        if (popped != null) {
            ifContained.accept(popped);
        }
    }

    /**
     * Returns if there is an active ticket currently
     *
     * @return if a ticket is present
     */
    public boolean isPresent() {
        return this.ticket.get() != null;
    }

    /**
     * Pushes a ticket object to the holder
     *
     * @param ticket
     *     the new ticket
     *
     * @throws IllegalStateException
     *     when a ticket is already present
     */
    public void propagate(T ticket) {
        if (!this.ticket.compareAndSet(null, ticket)) {
            throw new IllegalStateException("Ticket already propagated");
        }
    }

    /**
     * Fetches the current ticket if present, throws otherwise
     *
     * @return the active ticket
     *
     * @throws IllegalStateException
     *     if not present
     */
    @NonNull
    public T getOrThrow() {
        T popped = get();
        if (popped == null) throw new IllegalStateException("Not propagated");
        return popped;
    }
}
