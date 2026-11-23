package com.muoniumplayer.configskija.search;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.History;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reproduces unsafe historical call patterns; current host integration fixes are reviewed separately. */
public final class HistoryBoundaryAuditTest {
    public static void main(String[] args) {
        ArrayList<String> mutable = new ArrayList<>(List.of("before"));
        Map<String,Object> values = new LinkedHashMap<>(); values.put("list",mutable);
        History<Map<String,Object>> shallow = new History<>(10,()->Map.copyOf(values),snapshot->{values.clear();values.putAll(snapshot);});
        shallow.begin("edit list"); mutable.add("after");
        require(!shallow.commit() && !shallow.canUndo(),"Shallow snapshots share mutable elements, so in-place list edits can be invisible.");

        Map<String,Integer> shared = new LinkedHashMap<>(Map.of("page-a",1,"page-b",10));
        History<Map<String,Integer>> global = new History<>(10,()->Map.copyOf(shared),snapshot->{shared.clear();shared.putAll(snapshot);});
        global.begin("page-a edit");shared.put("page-a",2);shared.put("page-b",20);global.commit();global.undo();
        require(shared.get("page-b")==10,"A global snapshot also restores unrelated changes made within the same transaction.");

        AtomicInteger value=new AtomicInteger(1);AtomicBoolean locked=new AtomicBoolean();
        ConfigHost.Option option=new ConfigHost.Option("stable","Value","",ConfigHost.Kind.SLIDER,0,10,1,List.of(),value::get,next->value.set(((Number)next).intValue()),()->locked.get()?"locked":"",()->{});
        History<Integer> gated=new History<>(10,value::get,option::set);gated.begin("change");option.set(2);gated.commit();locked.set(true);
        require(gated.undo()&&value.get()==2&&!gated.canUndo(),"An enabled-gated restore may consume undo without restoring its target value.");
        System.out.println("HistoryBoundaryAuditTest: 3 historical unsafe call patterns reproduced; current host fixes reviewed separately.");
        System.out.println("RISK shallow mutable snapshots; RISK cross-page transaction ownership; RISK disabled setter during restore");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
