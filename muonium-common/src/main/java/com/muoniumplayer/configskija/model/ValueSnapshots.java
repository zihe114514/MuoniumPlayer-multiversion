package com.muoniumplayer.configskija.model;

import java.lang.reflect.Array;
import java.util.*;

/** Detached configuration values. Mutable collections and arrays never alias a history snapshot. */
public final class ValueSnapshots {
    private ValueSnapshots() {}
    public static Object copy(Object value) {
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean||value instanceof Enum<?>||value instanceof java.awt.Color||value.getClass().isRecord()) return value;
        if(value instanceof List<?> list) { List<Object> copy=new ArrayList<>();for(Object item:list)copy.add(copy(item));return Collections.unmodifiableList(copy); }
        if(value instanceof Set<?> set) { Set<Object> copy=new LinkedHashSet<>();for(Object item:set)copy.add(copy(item));return Collections.unmodifiableSet(copy); }
        if(value instanceof Map<?,?> map) { Map<Object,Object> copy=new LinkedHashMap<>();map.forEach((key,item)->copy.put(copy(key),copy(item)));return Collections.unmodifiableMap(copy); }
        if(value.getClass().isArray()) { int n=Array.getLength(value);Object clone=Array.newInstance(value.getClass().getComponentType(),n);for(int i=0;i<n;i++)Array.set(clone,i,copy(Array.get(value,i)));return clone; }
        throw new IllegalArgumentException("No snapshot codec for "+value.getClass().getName());
    }
    public static boolean equal(Object a,Object b) {
        if(a==b)return true;if(a==null||b==null)return false;
        if(a.getClass().isArray()&&b.getClass().isArray()) { int n=Array.getLength(a);if(n!=Array.getLength(b))return false;for(int i=0;i<n;i++)if(!equal(Array.get(a,i),Array.get(b,i)))return false;return true; }
        if(a instanceof List<?> x&&b instanceof List<?> y) { if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!equal(x.get(i),y.get(i)))return false;return true; }
        if(a instanceof Map<?,?> x&&b instanceof Map<?,?> y) { if(!x.keySet().equals(y.keySet()))return false;for(Object key:x.keySet())if(!equal(x.get(key),y.get(key)))return false;return true; }
        return a.equals(b);
    }
}
