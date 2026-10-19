package org.dextur.reputation.domain.protection;

import java.math.*;

/** Six decimal places. HALF_EVEN at the final product; symmetric HALF_UP for the public total. */
public final class Fixed {
    public static final long SCALE=1_000_000;
    private Fixed() { }
    public static long decimal(Object value) {
        if(!(value instanceof Number)&&!(value instanceof String)) throw new IllegalArgumentException("Decimal required");
        return new BigDecimal(value.toString()).movePointRight(6).longValueExact();
    }
    public static long product(long... factors) {
        BigDecimal result=BigDecimal.ONE;
        for(long factor:factors) result=result.multiply(BigDecimal.valueOf(factor,6));
        return result.movePointRight(6).setScale(0,RoundingMode.HALF_EVEN).longValueExact();
    }
    public static long ratio(long numerator,long denominator) {
        if(numerator<0||denominator<=0) throw new IllegalArgumentException("Invalid ratio");
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(SCALE)).divide(BigDecimal.valueOf(denominator),0,RoundingMode.HALF_EVEN).longValueExact();
    }
    public static long units(long whole) { return Math.multiplyExact(whole,SCALE); }
    public static long display(long units) { return BigDecimal.valueOf(units,6).setScale(0,RoundingMode.HALF_UP).longValueExact(); }
    public static long clamp(long value,long min,long max) { return Math.max(min,Math.min(max,value)); }
    public static long interpolate(long min,long max,long fraction) { return Math.addExact(min,product(Math.subtractExact(max,min),clamp(fraction,0,SCALE))); }
}
