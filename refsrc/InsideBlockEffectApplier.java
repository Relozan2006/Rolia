package net.minecraft.world.entity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.util.Util;

public interface InsideBlockEffectApplier {
    InsideBlockEffectApplier NOOP = new InsideBlockEffectApplier() {
        @Override
        public void apply(final InsideBlockEffectType type) {
        }

        @Override
        public void runBefore(final InsideBlockEffectType type, final Consumer<Entity> effect) {
        }

        @Override
        public void runAfter(final InsideBlockEffectType type, final Consumer<Entity> effect) {
        }
    };

    void apply(InsideBlockEffectType type);

    void runBefore(InsideBlockEffectType type, Consumer<Entity> effect);

    void runAfter(InsideBlockEffectType type, Consumer<Entity> effect);

    class StepBasedCollector implements InsideBlockEffectApplier {
        // Canvas start - optimize step based collector
        // heavily based off of https://github.com/Winds-Studio/Leaf/blob/ver/1.21.8/leaf-server/minecraft-patches/features/0273-optimize-checkInsideBlocks-calls.patch
        // modifications included for micro optimizations and slight performance increases
        private static final InsideBlockEffectType[] APPLY_ORDER = InsideBlockEffectType.values();

        private final Consumer<Entity>[] effectsInStep = new Consumer[APPLY_ORDER.length];
        private final it.unimi.dsi.fastutil.objects.ObjectArrayList<Consumer<Entity>>[] beforeEffectsInStep = new it.unimi.dsi.fastutil.objects.ObjectArrayList[APPLY_ORDER.length];
        private final it.unimi.dsi.fastutil.objects.ObjectArrayList<Consumer<Entity>>[] afterEffectsInStep = new it.unimi.dsi.fastutil.objects.ObjectArrayList[APPLY_ORDER.length];

        private final it.unimi.dsi.fastutil.objects.ObjectArrayList<Consumer<Entity>> finalEffects = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
        private int lastStep = -1;

        public StepBasedCollector() {
            for (int i = 0; i < APPLY_ORDER.length; i++) {
                beforeEffectsInStep[i] = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(2);
                afterEffectsInStep[i] = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(2);
            }
        }

        public void advanceStep(final int step, net.minecraft.core.BlockPos pos) {
            this.currentBlockPos = pos;
            if (this.lastStep != step) {
                this.lastStep = step;
                this.flushStep();
            }
        }

        public void applyAndClear(final Entity entity) {
            this.flushStep();

            List<Consumer<Entity>> effects = this.finalEffects;
            int size = effects.size();

            if (size == 0) {
                this.lastStep = -1;
                return;
            }

            if (!entity.isAlive()) {
                effects.clear();
                this.lastStep = -1;
                return;
            }

            int i = 0;
            while (i < size - 3) {
                effects.get(i++).accept(entity);
                effects.get(i++).accept(entity);
                effects.get(i++).accept(entity);
                effects.get(i++).accept(entity);
                if (!entity.isAlive()) {
                    break;
                }
            }

            if (entity.isAlive()) {
                for (; i < size; i++) {
                    effects.get(i).accept(entity);
                    if (!entity.isAlive()) {
                        break;
                    }
                }
            }

            effects.clear();
            this.lastStep = -1;
        }

        private void flushStep() {
            final int len = APPLY_ORDER.length;
            final Consumer<Entity>[] effectArr = this.effectsInStep;
            final List<Consumer<Entity>> finalList = this.finalEffects;

            for (int i = 0; i < len; i++) {
                List<Consumer<Entity>> beforeList = this.beforeEffectsInStep[i];
                if (!beforeList.isEmpty()) {
                    finalList.addAll(beforeList);
                    beforeList.clear();
                }

                Consumer<Entity> effect = effectArr[i];
                if (effect != null) {
                    finalList.add(effect);
                    effectArr[i] = null;
                }

                List<Consumer<Entity>> afterList = this.afterEffectsInStep[i];
                if (!afterList.isEmpty()) {
                    finalList.addAll(afterList);
                    afterList.clear();
                }
            }
        }

        @Override
        public void apply(final InsideBlockEffectType type) {
            effectsInStep[type.ordinal()] = recorded(type);
        }

        @Override
        public void runBefore(final InsideBlockEffectType type, final Consumer<Entity> effect) {
            beforeEffectsInStep[type.ordinal()].add(effect);
        }

        @Override
        public void runAfter(final InsideBlockEffectType type, final Consumer<Entity> effect) {
            afterEffectsInStep[type.ordinal()].add(effect);
        }

        // Paper start - track position inside effect was triggered on
        private net.minecraft.core.BlockPos currentBlockPos = null;

        private Consumer<Entity> recorded(final InsideBlockEffectType type) {
            return new RecordedEffect(this.currentBlockPos.immutable(), type.effect());
        }

        record RecordedEffect(
            net.minecraft.core.BlockPos blockPos,
            InsideBlockEffectType.Applier applier
        ) implements Consumer<Entity> {

            @Override
            public void accept(final Entity entity) {
                this.applier.affect(entity, blockPos);
            }
        }
        // Paper end - track position inside effect was triggered on
        // Canvas end - optimize step based collector
    }
}
