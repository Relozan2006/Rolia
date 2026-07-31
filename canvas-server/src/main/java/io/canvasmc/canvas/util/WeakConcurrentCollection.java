package io.canvasmc.canvas.util;

import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A concurrent collection version of {@link org.bukkit.craftbukkit.util.WeakCollection} from CraftBukkit
 *
 * @param <E>
 *     the element type
 *
 * @author dueris
 */
public class WeakConcurrentCollection<E> implements Collection<E> {
    private final CopyOnWriteArrayList<WeakReference<E>> backed = new CopyOnWriteArrayList<>();
    // Rolia - build 45: automatic compaction never ran, not once, in any build.
    //
    // The old trigger was `(backed.size() - liveCount) * 4 >= backed.size()`, where liveCount was
    // incremented in add() and decremented in remove() and compact() right beside the matching
    // change to `backed`. It therefore equalled backed.size() at all times, the left-hand side was
    // always zero, and the condition reduced to `0 >= total` - false whenever the collection held
    // anything at all. Entries whose referent had been garbage collected were never purged, so
    // CraftScoreboardManager.scoreboards grew for the entire life of the process on any server
    // whose plugins call getNewScoreboard(), and every traversal walked all of it.
    //
    // Rather than repair the estimate, ask the garbage collector. Each reference is registered with
    // this queue and the JVM enqueues it at the moment its referent is collected, so drainDead()
    // removes exactly the dead entries and nothing else - no counter to drift, no heuristic to
    // mistune, and no O(n) scan on a collection that has not lost anything.
    private final ReferenceQueue<E> deadRefs = new ReferenceQueue<>();

    @Override
    public int size() {
        int count = 0;
        for (E _ : this) count++;
        return count;
    }

    @Override
    public boolean isEmpty() {
        if (backed.isEmpty()) return true; // Rolia - build 45: the entry count IS backed.size(); no second counter to disagree with it
        for (WeakReference<E> ref : backed) {
            if (ref.get() != null) return false;
        }
        return true;
    }

    @Override
    public boolean contains(final Object o) {
        if (o == null) return false;
        for (E value : this) {
            if (o.equals(value)) return true;
        }
        return false;
    }

    @Override
    public @NonNull Iterator<E> iterator() {
        final List<WeakReference<E>> snapshot = List.copyOf(backed);
        return new Iterator<>() {
            private final Iterator<WeakReference<E>> it = snapshot.iterator();
            private @Nullable E next = null;
            private @Nullable E lastResolved = null;

            @Override
            public boolean hasNext() {
                if (next != null) return true;
                while (it.hasNext()) {
                    E value = it.next().get();
                    if (value != null) {
                        next = value;
                        return true;
                    }
                }
                return false;
            }

            @Override
            public @Nullable E next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                lastResolved = next;
                next = null;
                return lastResolved;
            }

            @Override
            public void remove() {
                Preconditions.checkState(lastResolved != null, "No element to remove, call next() first");
                WeakConcurrentCollection.this.remove(lastResolved);
                lastResolved = null;
            }
        };
    }

    @Override
    public @NonNull Object @NonNull [] toArray() {
        return new ObjectArrayList<>(this).toArray();
    }

    @Override
    public @NonNull <T> T @NonNull [] toArray(@NonNull final T @NonNull [] a) {
        return new ObjectArrayList<>(this).toArray(a);
    }

    @Override
    public boolean add(final E value) {
        Preconditions.checkArgument(value != null, "Cannot add null value");
        backed.add(new WeakReference<>(value, deadRefs)); // Rolia - build 45: registered, so the JVM tells us when it dies
        drainDead();
        return true;
    }

    @Override
    public boolean remove(final Object o) {
        if (o == null) return false;
        for (WeakReference<E> ref : backed) {
            E value = ref.get();
            if (o.equals(value)) {
                ref.clear(); // Rolia - build 45: clear() does not enqueue, so this entry will not also arrive via deadRefs
                backed.remove(ref);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsAll(@NonNull final Collection<?> c) {
        for (final Object o : c) {
            if (!contains(o)) return false;
        }
        return true;
    }

    @Override
    public boolean addAll(@NonNull final Collection<? extends E> c) {
        boolean changed = false;
        for (E value : c) changed |= add(value);
        return changed;
    }

    @Override
    public boolean removeAll(@NonNull final Collection<?> c) {
        boolean changed = false;
        for (WeakReference<E> ref : backed) {
            E value = ref.get();
            if (value != null && c.contains(value)) {
                ref.clear(); // Rolia - build 45: clear() does not enqueue, so this will not also arrive via deadRefs
                backed.remove(ref);
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean retainAll(@NonNull final Collection<?> c) {
        boolean changed = false;
        for (WeakReference<E> ref : backed) {
            E value = ref.get();
            if (value != null && !c.contains(value)) {
                ref.clear(); // Rolia - build 45: as above
                backed.remove(ref);
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public void clear() {
        for (WeakReference<E> ref : backed) ref.clear();
        backed.clear();
        while (deadRefs.poll() != null) { /* Rolia - build 45: discard notifications for entries that are already gone */ }
    }

    /**
     * Purges every entry whose referent has been garbage collected.
     *
     * <p>Rolia - build 45: this used to iterate the copy-on-write snapshot and call {@code remove}
     * per dead entry, which allocates a fresh backing array each time - quadratic on exactly the
     * collections that need compacting most. {@code removeIf} does it in one atomic pass.</p>
     */
    public void compact() {
        drainDead();
        backed.removeIf(ref -> ref.get() == null);
    }

    /**
     * Removes the entries the garbage collector has told us about. O(1) when nothing has died.
     */
    private void drainDead() {
        Reference<? extends E> dead = deadRefs.poll();
        if (dead == null) return;
        final List<Reference<? extends E>> batch = new ObjectArrayList<>();
        do {
            batch.add(dead);
        } while ((dead = deadRefs.poll()) != null);
        // one pass, not one copy of the backing array per dead entry
        backed.removeIf(batch::contains);
    }
}
