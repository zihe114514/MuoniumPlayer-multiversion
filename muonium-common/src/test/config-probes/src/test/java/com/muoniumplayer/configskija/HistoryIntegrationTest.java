package com.muoniumplayer.configskija;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HistoryIntegrationTest {
    public static void main(String[] args) {
        final Map<String,Object> values=new HashMap<>();values.put("list",new ArrayList<>(List.of("before")));values.put("other",1);
        AtomicBoolean disabled=new AtomicBoolean();
        ConfigHost.Option list=new ConfigHost.Option("list","Mutable list","",ConfigHost.Kind.CHOICE,0,1,0,List.of(),()->values.get("list"),value->values.put("list",new ArrayList<>((List<?>)value)),()->disabled.get()?"disabled":"",null);
        ConfigHost.Option other=new ConfigHost.Option("other","Other setting","",ConfigHost.Kind.SLIDER,0,100,1,List.of(),()->values.get("other"),value->values.put("other",value),()->"",null);
        ConfigHost host=new ConfigHost(){public List<Category> categories(){return List.of(new Category("test","Test","","",List.of(new Group("g","G",List.of(list,other)))));}public List<HudElement> huds(){return List.of();}public void persist(){}public void close(){}public String clipboard(){return "";}public void clipboard(String text){}public void error(String action,Throwable error){throw new AssertionError(action,error);}};
        ConfigView view=new ConfigView(host,new UiPreferences(Map.of("instantSearch","false"),()->{}));
        view.beginEdit(list);((List<String>)values.get("list")).add("after");values.put("other",22);view.commitEdit();
        disabled.set(true);view.key(ConfigView.Key.Z,"",true,false,false);
        check(values.get("list").equals(List.of("before")),"deep snapshot restored while the setting is disabled");
        check(values.get("other").equals(22),"unrelated setting is outside the transaction owner");
        view.key(ConfigView.Key.Z,"",true,true,false);check(values.get("list").equals(List.of("before","after")),"redo restores the detached target");
        check(values.get("other").equals(22),"redo leaves unrelated setting intact");
        int[] array={1,2};Object copy=ValueSnapshots.copy(array);array[0]=9;check(((int[])copy)[0]==1,"primitive arrays are detached");
        check(ValueSnapshots.equal(copy,new int[]{1,2}),"array snapshots compare by content");
        System.out.println("HistoryIntegrationTest PASS: 6 real integration regressions");
    }
    static void check(boolean test,String message){if(!test)throw new AssertionError(message);}
}
