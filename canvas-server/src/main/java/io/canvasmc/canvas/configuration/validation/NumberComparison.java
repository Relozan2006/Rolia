package io.canvasmc.canvas.configuration.validation;

import io.canvasmc.canvas.configuration.Part;

/**
 * Rolia - build 45: the bounds were stored as {@code float} and the value under test was compared
 * as {@code float}. A float carries 24 bits of mantissa, so every bound above about 16.7 million
 * was being compared on a rounded number - and a bound of {@code Long.MAX_VALUE} could not be
 * expressed at all, which matters because tick and memory limits are exactly the settings people
 * write large numbers into. Widened to {@code double}: 53 bits of mantissa covers every long a
 * config can hold up to 2^53, and every existing call site passes a float literal, which widens
 * silently.
 */
public record NumberComparison(Type type, double... n) implements Part.Validation<Number> {

    @Override
    public void validate(final Number number) {
        if (number == null) throw new IllegalArgumentException("Value must not be null");

        final double val = number.doubleValue();

        switch (type) {
            case GREATER_THAN -> {
                if (val <= n[0])
                    throw new IllegalArgumentException("Expected value > " + n[0] + ", got " + val);
            }
            case GREATER_THAN_OR_EQUAL_TO -> {
                if (val < n[0])
                    throw new IllegalArgumentException("Expected value >= " + n[0] + ", got " + val);
            }
            case LESS_THAN -> {
                if (val >= n[0])
                    throw new IllegalArgumentException("Expected value < " + n[0] + ", got " + val);
            }
            case LESS_THAN_OR_EQUAL_TO -> {
                if (val > n[0])
                    throw new IllegalArgumentException("Expected value <= " + n[0] + ", got " + val);
            }
            case EQUAL -> {
                if (val != n[0])
                    throw new IllegalArgumentException("Expected value == " + n[0] + ", got " + val);
            }
            case BETWEEN -> {
                if (n.length < 2)
                    throw new IllegalStateException("BETWEEN requires two bounds, only one was provided");
                if (val < n[0] || val > n[1])
                    throw new IllegalArgumentException("Expected value between " + n[0] + " and " + n[1] + ", got " + val);
            }
        }
    }

    public enum Type {
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL_TO,
        BETWEEN,
        EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL_TO
    }
}
