package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.ui.Geometry;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Numeric contracts transcribed from SliderControl/NumberOption/RangeOption, independent of rendering. */
public final class ControlMath {
    private ControlMath() {}
    public static double snap(double value, double min, double max, double step) {
        value = Geometry.clamp(value, min, max);
        return step > 0 ? Geometry.clamp(min + Math.floor((value - min) / step + .5) * step, min, max) : value;
    }
    public static double sliderValue(double x, double width, double thumb, double min, double max, double step) {
        return snap(min + Geometry.clamp((x - thumb / 2) / Math.max(1, width - thumb), 0, 1) * (max - min), min, max, step);
    }
    public static double fraction(double value, double min, double max) { return max == min ? 0 : Geometry.clamp((value - min) / (max - min), 0, 1); }
    public static String filterNumber(String input) {
        StringBuilder out = new StringBuilder(); boolean dot = false;
        for (int i = 0; i < input.length(); i++) { char c = input.charAt(i);
            if (c == '-' && i == 0) out.append(c);
            else if (c == '.' && !dot) { dot = true; out.append(c); }
            else if (Character.isDigit(c)) out.append(c);
        }
        return out.toString();
    }
    public static Double parse(String text, double min, double max) {
        try { double value = Double.parseDouble(text); return Double.isFinite(value) ? Geometry.clamp(value, min, max) : null; }
        catch (NumberFormatException e) { return null; }
    }
    public static String format(double value) { return value == (long)value ? Long.toString((long)value) : String.format(Locale.ROOT, "%.2f", value); }
    public static double spinnerStep(double step, double min, double max) { return step > 0 ? step : min == Math.rint(min) && max == Math.rint(max) ? 1 : .1; }
    public static Number numberLike(double value, Object current, boolean round) {
        double v = round ? Math.floor(value + .5) : value;
        if (current instanceof Integer) return (int)v;
        if (current instanceof Long) return (long)v;
        if (current instanceof Short) return (short)v;
        if (current instanceof Byte) return (byte)v;
        if (current instanceof Float) return (float)value;
        return value;
    }
    public static List<Object> elements(Object value) {
        List<Object> result = new ArrayList<>();
        if (value != null && value.getClass().isArray()) for (int i = 0; i < Array.getLength(value); i++) result.add(Array.get(value, i));
        else if (value instanceof Iterable<?> values) for (Object v : values) result.add(v);
        return result;
    }
    public static Object containerLike(Object current, List<?> values, boolean round) {
        if (current == null || !current.getClass().isArray()) {
            Object exemplar = current instanceof List<?> list ? list.stream().filter(v -> v instanceof Number).findFirst().orElse(null) : null;
            List<Object> result = new ArrayList<>(); for (Object value : values) result.add(value instanceof Number n && exemplar != null ? numberLike(n.doubleValue(), exemplar, round) : value);
            return result;
        }
        Class<?> component = current.getClass().getComponentType(); Object result = Array.newInstance(component, values.size());
        Object exemplar;
        if (component == int.class || component == Integer.class) exemplar = Integer.valueOf(0);
        else if (component == long.class || component == Long.class) exemplar = Long.valueOf(0);
        else if (component == float.class || component == Float.class) exemplar = Float.valueOf(0);
        else if (component == short.class || component == Short.class) exemplar = Short.valueOf((short)0);
        else if (component == byte.class || component == Byte.class) exemplar = Byte.valueOf((byte)0);
        else exemplar = Double.valueOf(0);
        for (int i = 0; i < values.size(); i++) { Object value = values.get(i); Array.set(result, i, value instanceof Number n ? numberLike(n.doubleValue(), exemplar, round) : value); }
        return result;
    }
    /** The persisted pair is ordered while a focused editor can keep its unsorted draft. */
    public static List<Double> ordered(double start, double end) { return List.of(Math.min(start, end), Math.max(start, end)); }
}
