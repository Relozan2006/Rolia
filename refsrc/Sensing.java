package net.minecraft.world.entity.ai.sensing;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

public class Sensing {
    private final Mob mob;
    private final IntSet seen = new IntOpenHashSet();
    private final IntSet unseen = new IntOpenHashSet();

    public Sensing(final Mob mob) {
        this.mob = mob;
    }

    public void tick() {
        this.seen.clear();
        this.unseen.clear();
    }

    public boolean hasLineOfSight(final Entity target) {
        int targetId = target.getId();
        if (this.seen.contains(targetId)) {
            return true;
        }

        if (this.unseen.contains(targetId)) {
            return false;
        }

        boolean hasLineOfSight = this.mob.hasLineOfSight(target);
        if (hasLineOfSight) {
            this.seen.add(targetId);
        } else {
            this.unseen.add(targetId);
        }

        return hasLineOfSight;
    }
}
