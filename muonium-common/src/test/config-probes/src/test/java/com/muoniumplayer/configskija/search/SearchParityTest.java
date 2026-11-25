package com.muoniumplayer.configskija.search;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.SearchDocuments;
import com.muoniumplayer.configskija.model.SearchIndex;
import com.muoniumplayer.configskija.model.SearchIndex.Document;
import com.muoniumplayer.configskija.model.SearchIndex.Metadata;
import com.muoniumplayer.configskija.model.SearchIndex.Scope;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.UiPage;
import com.muoniumplayer.configskija.ui.search.SearchPage;
import com.muoniumplayer.configskija.ui.search.SearchPages;
import com.muoniumplayer.configskija.ui.search.SearchSession;
import com.muoniumplayer.configskija.ui.search.SettingsSearchAdapter;
import com.muoniumplayer.configskija.ui.settings.SettingsSchema;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Deterministic behavior harness: no Gradle, Minecraft, Kotlin, disk configs, real clocks or sleeps. */
public final class SearchParityTest {
    private static int checks;
    private static final List<String> passed = new ArrayList<>();
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void equal(Object actual, Object expected, String message) { checks++; if (!Objects.equals(actual, expected)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual); }
    private static void test(String name, Runnable run) { run.run(); passed.add(name); }
    public static void main(String[] args) {
        test("matching-delimiters-unicode-and-distance", SearchParityTest::matching);
        test("bounded-distance-against-independent-matrix", SearchParityTest::distanceOracle);
        test("scope-specific-fields-and-order", SearchParityTest::scopes);
        test("provider-priority-fallback-seed-and-stale-results", SearchParityTest::providers);
        test("incremental-sources-dependencies-failure-and-identity", SearchParityTest::sources);
        test("debounce-and-no-post-close-indexing", SearchParityTest::debounce);
        test("stable-document-identities-and-accordion-rows", SearchParityTest::rows);
        test("schema-visibility-and-local-field-or", SearchParityTest::schema);
        test("async-generation-and-close-rejection", SearchParityTest::session);
        test("extensible-page-query-and-global-page-integration", SearchParityTest::pages);
        System.out.println("SearchParityTest PASS: " + passed.size() + " groups, " + checks + " assertions");
        passed.forEach(name -> System.out.println("PASS " + name));
    }
    private static void matching() {
        try (SearchIndex defaults = new SearchIndex()) { equal(defaults.searchDistance(), 2, "default distance"); }
        check(SearchIndex.matches("Alpha", "alhpa", 2), "two substitutions match transposition at distance 2");
        check(!SearchIndex.matches("Alpha", "alhpa", 1), "Levenshtein is not Damerau");
        check(SearchIndex.matches("Alpha audio", "audii", 1), "fuzzy individual word");
        check(!SearchIndex.matches("Alpha audio", "audii", 0), "zero distance only substring");
        check(!SearchIndex.matches("abc", "xyz", -1), "negative distance disables fuzzy");
        equal(SearchIndex.tokenize("  a.b_c-d/e// "), List.of("a","b","c","d","e"), "five exact separators");
        equal(SearchIndex.tokenize("a\tb:c,\\d中文"), List.of("a\tb:c,\\d中文"), "not generic word segmentation");
        check(!SearchIndex.matches("Alpha volume beta", "alpha beta", 0), "query is not split into AND words");
        check(SearchIndex.matches("中文歌词", "中文歌时", 1), "UTF-16 CJK edits");
        equal(SearchIndex.levenshtein("😀", "", 0), 2, "UTF-16 surrogate length");
        check(SearchIndex.matches("anything", "", 0), "helper empty substring contract");
        check(!SearchIndex.matches(null, "x", 2), "nullable metadata");
        equal(SearchIndex.trimQuery("\u00a0\t Alpha \u2003"), "Alpha", "Kotlin whitespace trimming");
        Locale old = Locale.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); check(SearchIndex.matches("TITLE", "title", 0), "locale-invariant folding"); }
        finally { Locale.setDefault(old); }
    }
    private static void distanceOracle() {
        List<String> words = new ArrayList<>(); words.add("");
        for (int length = 1; length <= 5; length++) for (int n = 0; n < 1 << length; n++) {
            StringBuilder word = new StringBuilder(); for (int bit = 0; bit < length; bit++) word.append((n & 1 << bit) == 0 ? 'a' : 'b'); words.add(word.toString());
        }
        for (String a : words) for (String b : words) for (int max = 0; max <= 3; max++) {
            int expected = fullDistance(a, b), bounded = SearchIndex.levenshtein(a, b, max);
            equal(bounded <= max, expected <= max, "bounded membership " + a + "/" + b + "/" + max);
            if (expected <= max) equal(bounded, expected, "bounded exact value");
        }
    }
    private static int fullDistance(String a, String b) {
        int[][] matrix = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) matrix[i][0] = i;
        for (int j = 0; j <= b.length(); j++) matrix[0][j] = j;
        for (int i = 1; i <= a.length(); i++) for (int j = 1; j <= b.length(); j++) matrix[i][j] = Math.min(Math.min(matrix[i-1][j]+1, matrix[i][j-1]+1), matrix[i-1][j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
        return matrix[a.length()][b.length()];
    }
    private static Metadata meta(String title, String id, String description, String category, String subcategory, List<String> tags, String mod, String path) {
        return new Metadata(title,id,description,"section-only",category,subcategory,tags,mod,"mod-description-only",path);
    }
    private static Document<?> document(String id, Set<Scope> scopes, Metadata metadata) { return new Document<>(id,scopes,metadata,new Object()); }
    private static Document<?> named(String id, String title, Set<Scope> scopes) { return document(id,scopes,Metadata.title(title)); }
    private static List<String> ids(List<Document<?>> hits) { return hits.stream().map(Document::id).toList(); }
    private static void scopes() {
        ManualWorker worker = new ManualWorker();
        try (SearchIndex index = new SearchIndex(0,worker,ignored -> {})) {
            MutableSource source = new MutableSource();
            source.value = List.of(
                    document("mod",Set.of(Scope.MODS),meta("Visible","internal-mod","hidden-description","mod-category",null,List.of("alias"),null,null)),
                    document("option",Set.of(Scope.OPTIONS,Scope.config("owner")),meta("Title","internal-id","tooltip","category-name","sub-name",List.of("tag"),"Owner","property/path")),
                    document("hud",Set.of(Scope.HUDS),meta("Hud title","hud-id","hud-description","hud-category",null,List.of(),"Hud Owner",null)),
                    document("key",Set.of(Scope.KEYBINDS),meta("Bind","bind-id","bind-description","bind-category",null,List.of(),"Key Owner","special/path")),
                    named("first","fuzzy special",Set.of(Scope.OPTIONS)), named("last","special",Set.of(Scope.OPTIONS)));
            index.registerSource(source); index.rebuildNow();
            equal(ids(index.search("hidden-description",Set.of(Scope.MODS))),List.of(),"mods cannot match description");
            equal(ids(index.search("internal-mod",Set.of(Scope.MODS))),List.of(),"mods cannot match id");
            equal(ids(index.search("alias",Set.of(Scope.MODS))),List.of("mod"),"mods tags");
            equal(ids(index.search("tooltip",Set.of(Scope.OPTIONS))),List.of("option"),"option description");
            equal(ids(index.search("category-name",Set.of(Scope.OPTIONS))),List.of(),"global option excludes category");
            equal(ids(index.search("category-name",Set.of(Scope.config("owner")))),List.of("option"),"local config category");
            equal(ids(index.search("internal-id",Set.of(Scope.config("owner")))),List.of("option"),"local config id");
            equal(ids(index.search("property/path",Set.of(Scope.config("owner")))),List.of(),"config does not search metadata path");
            equal(ids(index.search("Hud Owner",Set.of(Scope.HUDS))),List.of("hud"),"hud owner title");
            equal(ids(index.search("Key Owner",Set.of(Scope.KEYBINDS))),List.of(),"keybind excludes owner title");
            equal(ids(index.search("special/path",Set.of(Scope.KEYBINDS))),List.of("key"),"keybind path");
            equal(ids(index.search("section-only",Set.of(Scope.OPTIONS,Scope.HUDS,Scope.KEYBINDS))),List.of(),"section excluded");
            equal(ids(index.search("mod-description-only",Set.of(Scope.OPTIONS))),List.of(),"owner description excluded");
            equal(ids(index.search("special",Set.of(Scope.OPTIONS))),List.of("first","last"),"no exact-match reranking");
            equal(index.search(" \u00a0\t ",Set.of(Scope.OPTIONS)),List.of(),"blank corpus query empty");
            equal(index.search("special",Set.of()),List.of(),"empty default scopes");
            equal(new ArrayList<>(index.searchGrouped("special",Set.of(Scope.OPTIONS),d -> d.id().equals("first")?"A":"B").keySet()),List.of("A","B"),"group first appearance order");
        }
    }
    private static void providers() {
        ManualWorker worker = new ManualWorker(); List<SearchIndex.Failure> failures = new ArrayList<>();
        try (SearchIndex index = new SearchIndex(0,worker,failures::add)) {
            MutableSource source = new MutableSource(); source.value=List.of(named("a","matching",Set.of(Scope.OPTIONS)),named("b","matching",Set.of(Scope.OPTIONS)));
            index.registerSource(source); index.init(); index.rebuildNow();
            Probe unavailable = new Probe(100); unavailable.available=false; Probe high = new Probe(50); high.results=List.of("b","missing","a","b");
            Probe equal = new Probe(50); equal.results=List.of("a"); Probe low=new Probe(1); low.results=List.of("a");
            index.registerProvider(unavailable); index.registerProvider(high); index.registerProvider(equal); index.registerProvider(low); worker.advance(0);
            equal(high.updates.size(),1,"late provider seeded"); equal(high.updates.get(0),List.of("a","b"),"full seed order");
            equal(ids(index.search("irrelevant",Set.of(Scope.MODS))),List.of("b","a","b"),"provider rank/duplicates preserved; no invented scope filtering");
            equal(low.calls,0,"providers are selected, not unioned"); equal(equal.calls,0,"equal priority registration stable");
            high.searchAction=()-> {throw new IllegalStateException("broken provider");};
            equal(ids(index.search("matching",Set.of(Scope.OPTIONS))),List.of("a","b"),"failure returns default, not next provider");
            equal(equal.calls,0,"query failure does not select second provider");
            check(failures.stream().anyMatch(f->f.operation().equals("provider query")),"query failure reported");
            high.searchAction=null; high.available=false;
            equal(ids(index.search("matching",Set.of(Scope.OPTIONS))),List.of("a"),"unavailable high goes to equal next");
            index.unregisterProvider(equal); high.available=true;
            Document<?> oldA=index.document("a"), replacement=named("a","new title",Set.of(Scope.OPTIONS));
            high.results=List.of("b","a"); high.searchAction=()-> {source.value=List.of(replacement);index.invalidate(source);index.rebuildNow();};
            List<Document<?>> live=index.search("matching",Set.of(Scope.OPTIONS));equal(ids(live),List.of("a"),"stale removed ids dropped");check(live.get(0)==replacement,"replaced payload re-resolved");check(live.get(0)!=oldA,"old record not leaked");
            high.searchAction=null; high.availableAction=()->{throw new IllegalStateException("availability");};
            equal(ids(index.search("matching",Set.of(Scope.OPTIONS))),List.of("a"),"availability failure chooses lower ready provider");
            check(failures.stream().anyMatch(f->f.operation().equals("provider availability")),"availability diagnosed");
        }
    }
    private static void sources() {
        ManualWorker worker=new ManualWorker();List<SearchIndex.Failure> failures=new ArrayList<>();
        try(SearchIndex index=new SearchIndex(2,worker,failures::add)) {
            MutableSource a=new MutableSource(),b=new MutableSource(),c=new MutableSource();b.dependencies=Set.of(a);c.dependencies=Set.of(b);
            Object payload=new Object();Document<?> original=new Document<>("a",Set.of(Scope.OPTIONS),Metadata.title("Alpha"),payload);a.value=List.of(original);b.value=List.of(named("b","Beta",Set.of(Scope.OPTIONS)));c.value=List.of(named("c","Gamma",Set.of(Scope.OPTIONS)));
            index.registerSource(a);index.registerSource(b);index.registerSource(c);SearchIndex.Update initial=index.rebuildNow();equal(ids(initial.added()),List.of("a","b","c"),"source merge order");
            index.rebuildNow();equal(List.of(a.calls,b.calls,c.calls),List.of(1,1,1),"unchanged sources cached");
            a.value=List.of(new Document<>("a",Set.of(Scope.OPTIONS),Metadata.title("Alpha"),payload));index.invalidate(a);SearchIndex.Update same=index.rebuildNow();equal(same.added(),List.of(),"content plus payload identity retains document");check(index.document("a")==original,"stable object retained");equal(List.of(a.calls,b.calls,c.calls),List.of(2,2,2),"transitive dependencies refreshed");
            a.value=List.of(new Document<>("a",Set.of(Scope.OPTIONS),Metadata.title("Alpha"),new Object()));index.invalidate(a);check(index.rebuildNow().added().stream().anyMatch(d->d.id().equals("a")),"payload replacement upserted even equal text");
            a.fail=true;index.invalidate(a);index.rebuildNow();check(index.document("a")!=null,"failed source retains prior docs");int previous=a.calls;index.rebuildNow();equal(a.calls,previous+1,"failed source remains dirty");a.fail=false;
            MutableSource duplicate=new MutableSource();duplicate.value=List.of(named("a","shadow",Set.of(Scope.OPTIONS)),named("d","Delta",Set.of(Scope.OPTIONS)));index.registerSource(duplicate);index.rebuildNow();equal(index.document("a").metadata().title(),"Alpha","first duplicate wins");check(failures.stream().anyMatch(f->f.operation().equals("duplicate document id")),"duplicate diagnosed");
            index.unregisterSource(a);SearchIndex.Update removed=index.rebuildNow();equal(index.document("a").metadata().title(),"shadow","next source supplies former duplicate");index.unregisterSource(duplicate);removed=index.rebuildNow();check(removed.removed().containsAll(Set.of("a","d")),"unregister removes only lost docs");
            check(failures.stream().anyMatch(f->f.operation().equals("document source")),"source failure diagnosed");
        }
    }
    private static void debounce() {
        ManualWorker worker=new ManualWorker();SearchIndex index=new SearchIndex(2,worker,ignored->{});MutableSource source=new MutableSource();source.value=List.of(named("a","Alpha",Set.of(Scope.OPTIONS)));
        index.registerSource(source);worker.advance(1000);equal(source.calls,0,"no indexing before init");index.init();worker.advance(249);equal(source.calls,0,"250ms debounce");index.invalidate(source);worker.advance(249);equal(source.calls,0,"subsequent registration restarts debounce");worker.advance(1);equal(source.calls,1,"exact debounce deadline indexes");
        index.invalidate(source);index.close();worker.advance(1000);equal(source.calls,1,"close rejects pending indexing");check(!worker.isShutdown(),"injected worker remains caller-owned");equal(index.documents(),Map.of(),"close releases corpus");
    }
    private static ConfigHost.Option option(String id,String title,String description) {
        return new ConfigHost.Option(id,title,description,ConfigHost.Kind.TOGGLE,0,1,0,List.of(),()->false,value->{},()->"",()->{});
    }
    private static void rows() {
        ConfigHost.Option head=option("enabled","Enable",""),a=option("audio.volume","Volume","loudness"),b=option("audio.balance","Balance","stereo");
        ConfigHost.Group accordion=new ConfigHost.Group("audio.panel","Audio",List.of(head,a,b));
        ConfigHost.Category category=new ConfigHost.Category("music","Music","","",List.of(accordion));
        Set<ConfigHost.Option> hidden=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        SearchDocuments.SettingsAdapter adapter=new SearchDocuments.SettingsAdapter(){
            public SearchDocuments.GroupData group(ConfigHost.Category c,ConfigHost.Group g){return new SearchDocuments.GroupData(true,head,true,List.of("sound"));}
            public boolean hidden(ConfigHost.Option option){return hidden.contains(option);}
        };
        SearchDocuments.SettingsSnapshot snapshot=SearchDocuments.settings("muonium","Muonium",List.of(category),adapter);
        equal(ids(snapshot.documents()),List.of("mod::muonium","muonium::audio.panel","muonium::audio.volume","muonium::audio.balance"),"persistent owner/path IDs");
        SearchDocuments.Row row=snapshot.rows().rowOfPayload(a);check(row==snapshot.rows().rowOfPayload(b),"accordion children share identity row");check(row==snapshot.rows().rowOfPayload(head),"toggle head owned by accordion row");equal(row.groupLabel(),null,"one category/one subcategory has no group heading");
        Document<?> child=snapshot.documents().get(2),tree=snapshot.documents().get(1);
        equal(snapshot.rows().narrow(row,List.of(child)).body(),List.of(a),"child hit narrows accordion");equal(snapshot.rows().narrow(row,List.of(tree)).body(),List.of(a,b),"tree hit preserves whole body");
        hidden.add(a);equal(snapshot.rows().narrow(row,List.of(child)),null,"runtime hidden hit omitted");check(snapshot.documents().contains(child),"runtime visibility is not static metadata exclusion");hidden.add(b);hidden.add(head);equal(snapshot.rows().narrow(row,List.of(tree)),null,"fully hidden accordion omitted");hidden.clear();
        equal(new ArrayList<>(snapshot.rows().globalGroups(List.of(child,tree)).keySet()),List.of("Muonium"),"global heading once in first-hit order");
        ConfigHost.Option translated=new ConfigHost.Option(a.id(),"音量",a.description(),a.kind(),0,1,0,List.of(),a.read(),a.write(),a.disabledReason(),a.reset());
        ConfigHost.Category translatedCategory=new ConfigHost.Category("music","音乐","","",List.of(new ConfigHost.Group("audio.panel","音频",List.of(head,translated,b))));
        SearchDocuments.SettingsSnapshot translatedSnapshot=SearchDocuments.settings("muonium","Muonium",List.of(translatedCategory),adapter);
        equal(ids(translatedSnapshot.documents()),ids(snapshot.documents()),"localization leaves IDs stable");
        SearchDocuments.Owner privateOwner=new SearchDocuments.Owner("private","Private",null,false,false);
        SearchDocuments.SettingsSnapshot privateDocs=SearchDocuments.settings(privateOwner,List.of(category),SearchDocuments.DEFAULT);
        check(privateDocs.documents().stream().allMatch(d->!d.scopes().contains(Scope.OPTIONS)&&d.scopes().contains(Scope.config("private"))),"non-global owner still locally searchable");
        ConfigHost.Option same=new ConfigHost.Option(a.id(),a.title(),a.description(),a.kind(),a.min(),a.max(),a.step(),a.choices(),a.read(),a.write(),a.disabledReason(),a.reset(),a.liveTitle());
        check(a.equals(same)&&a!=same,"fixture has equal-value distinct record");check(snapshot.rows().rowOfPayload(same)==null,"identity lookup cannot alias equal-value records");
        SearchDocuments.SettingsAdapter staticallyHidden=new SearchDocuments.SettingsAdapter(){public SearchDocuments.GroupData group(ConfigHost.Category c,ConfigHost.Group g){return new SearchDocuments.GroupData(true,head,false,List.of());}};
        equal(SearchDocuments.settings("owner","Owner",List.of(category),staticallyHidden).documents().size(),1,"static-hidden tree excludes all descendants; mod document remains");
    }
    private static void schema() {
        ConfigHost.Option option=option("stable.id","Volume","Sound level");AtomicReference<Boolean> visible=new AtomicReference<>(true);
        SettingsSchema schema=new SettingsSchema(){public OptionStyle option(ConfigHost.Option o){OptionStyle style=new OptionStyle(Visualizer.KEYBIND);style.visible=visible::get;return style;}};
        SettingsSearchAdapter adapter=new SettingsSearchAdapter(schema);ConfigHost.Group group=new ConfigHost.Group("g","Audio",List.of(option));ConfigHost.Category category=new ConfigHost.Category("c","Music","","",List.of(group));
        SearchDocuments.SettingsSnapshot snapshot=SearchDocuments.settings("owner","Owner",List.of(category),adapter);Document<?> document=snapshot.documents().get(1);
        check(document.scopes().contains(Scope.KEYBINDS),"schema keybind scope added");visible.set(false);check(adapter.hidden(option),"live display supplier evaluated on UI");
        check(SettingsSearchAdapter.matches(option,"Music","Audio","Volme",1),"local fuzzy title");check(SettingsSearchAdapter.matches(option,"Music","Audio","audio",0),"field OR matches category alone");check(!SettingsSearchAdapter.matches(option,"Music","Audio","Volume Audio",0),"cross-field query must not concatenate");
    }
    private static void session() {
        ManualWorker worker=new ManualWorker();List<Runnable> ui=new ArrayList<>();List<SearchSession.Result> delivered=new ArrayList<>();
        try(SearchIndex index=new SearchIndex(0,worker,ignored->{});SearchSession session=new SearchSession(index,ui::add)) {
            MutableSource source=new MutableSource();source.value=List.of(named("a","Alpha",Set.of(Scope.OPTIONS)),named("b","Beta",Set.of(Scope.OPTIONS)));index.registerSource(source);index.rebuildNow();
            session.submit("Alpha",Set.of(Scope.OPTIONS),delivered::add,error->{throw new AssertionError(error);});session.submit("Beta",Set.of(Scope.OPTIONS),delivered::add,error->{throw new AssertionError(error);});worker.advance(0);
            equal(delivered.size(),0,"completion marshalled to UI executor");drain(ui);equal(delivered.size(),1,"older request cannot overwrite newer");equal(ids(delivered.get(0).documents()),List.of("b"),"latest query result");
            session.submit("Alpha",Set.of(Scope.OPTIONS),delivered::add,error->{throw new AssertionError(error);});worker.advance(0);source.value=List.of();index.invalidate(source);index.rebuildNow();drain(ui);equal(delivered.get(1).documents(),List.of(),"removed result re-resolved before UI delivery");
            session.submit("Beta",Set.of(Scope.OPTIONS),delivered::add,error->{throw new AssertionError(error);});worker.advance(0);session.close();drain(ui);equal(delivered.size(),2,"closed session suppresses late completion");
        }
    }
    private static void pages() {
        SearchPages pages=new SearchPages();SearchPages.Group first=new SearchPages.Group("sample:a","A",100),second=new SearchPages.Group("sample:b","B",200);UiPage empty=context->{};
        pages.register(new SearchPages.Page("sample:second","Second","x",second,empty));pages.register(new SearchPages.Page("sample:late","Late","x",first,5,true,"Search late",Set.of(Scope.OPTIONS),ignored->empty));pages.register(new SearchPages.Page("sample:early","Early","x",first,empty));
        equal(pages.all().stream().map(SearchPages.Page::id).toList(),List.of("sample:early","sample:late","sample:second"),"page group/order stable sorting");
        boolean duplicate=false;try{pages.register(pages.byId("sample:early"));}catch(IllegalArgumentException expected){duplicate=true;}check(duplicate,"duplicate page refused");
        boolean bad=false;try{new SearchPages.Group("Upper:Bad","bad",0);}catch(IllegalArgumentException expected){bad=true;}check(bad,"namespaced lowercase IDs");
        com.muoniumplayer.configskija.model.UiPreferences preferences=new com.muoniumplayer.configskija.model.UiPreferences(Map.of("searchDistance","0"),()->{});
        AtomicReference<String> query=new AtomicReference<>("Alpha");PageContext context=(PageContext)Proxy.newProxyInstance(PageContext.class.getClassLoader(),new Class<?>[]{PageContext.class},(proxy,method,args)->switch(method.getName()){case "query"->query.get();case "preferences"->preferences;default->null;});
        equal(pages.byId("sample:late").query(context),"Alpha","custom page reads PageContext query");
        ManualWorker worker=new ManualWorker();List<Runnable> ui=new ArrayList<>();List<SearchPage.Results> results=new ArrayList<>();AtomicInteger searching=new AtomicInteger();
        ConfigHost.Option option=option("alpha","Alpha","");ConfigHost.Category category=new ConfigHost.Category("category","Category","","",List.of(new ConfigHost.Group("group","Group",List.of(option))));SearchDocuments.SettingsSnapshot snapshot=SearchDocuments.settings("owner","Owner",List.of(category));
        try(SearchIndex index=new SearchIndex(0,worker,ignored->{});SearchPage page=new SearchPage(index,snapshot.rows(),ui::add,new SearchPage.Renderer(){public void searching(PageContext ctx){searching.incrementAndGet();}public void results(PageContext ctx,SearchPage.Results value){results.add(value);}public void failed(PageContext ctx,Throwable error){throw new AssertionError(error);}})){
            index.registerSource(snapshot);index.rebuildNow();page.render(context);equal(searching.get(),1,"global page starts pending");worker.advance(0);drain(ui);page.render(context);equal(results.size(),1,"global page renders after async completion");check(!results.get(0).empty(),"global page groups option hits");
            query.set("missing");page.render(context);equal(results.size(),2,"previous results stay drawn while next query loads");equal(results.get(1).query(),"Alpha","pending content remains tagged with completed query");check(!results.get(1).empty(),"pending search keeps previous actual hits");page.leave(context);worker.advance(0);drain(ui);equal(results.size(),2,"leave rejects obsolete query completion");page.render(context);worker.advance(0);drain(ui);page.render(context);check(results.get(results.size()-1).empty(),"reentry starts current query");
            query.set("Alhpa");preferences.set("searchDistance",0);page.render(context);worker.advance(0);drain(ui);page.render(context);check(results.get(results.size()-1).empty(),"live distance zero disables fuzzy");
            preferences.set("searchDistance",2);page.render(context);worker.advance(0);drain(ui);page.render(context);check(!results.get(results.size()-1).empty(),"live preference change requeries identical query");
        }
    }
    private static void drain(List<Runnable> tasks){while(!tasks.isEmpty()){List<Runnable> copy=List.copyOf(tasks);tasks.clear();copy.forEach(Runnable::run);}}
    private static final class MutableSource implements SearchIndex.Source {
        List<Document<?>> value=List.of();Set<SearchIndex.Source> dependencies=Set.of();boolean fail;int calls;
        public List<Document<?>> documents(){calls++;if(fail)throw new IllegalStateException("source failed");return value;}
        public Set<SearchIndex.Source> dependencies(){return dependencies;}
    }
    private static final class Probe implements SearchIndex.Provider {
        final int priority;boolean available=true;List<String> results=List.of();int calls;Runnable searchAction,availableAction;List<List<String>> updates=new ArrayList<>();
        Probe(int priority){this.priority=priority;}public int priority(){return priority;}public boolean isAvailable(){if(availableAction!=null)availableAction.run();return available;}
        public List<String> search(String query,Set<Scope> scopes){calls++;if(searchAction!=null)searchAction.run();return results;}
        public void onCorpusUpdate(List<Document<?>> added,Set<String> removed){updates.add(ids(added));}
    }
    /** Explicit time progression makes debounce and late-completion tests deterministic. */
    static final class ManualWorker extends AbstractExecutorService implements ScheduledExecutorService {
        long now,sequence;boolean shutdown;final List<Task<?>> tasks=new ArrayList<>();
        final class Task<V> extends FutureTask<V> implements RunnableScheduledFuture<V>{
            final long due,order;Task(Callable<V> callable,long delay){super(callable);due=now+delay;order=sequence++;}
            public long getDelay(TimeUnit unit){return unit.convert(due-now,TimeUnit.MILLISECONDS);}public int compareTo(Delayed other){Task<?> task=(Task<?>)other;int compare=Long.compare(due,task.due);return compare==0?Long.compare(order,task.order):compare;}public boolean isPeriodic(){return false;}
        }
        public <V> ScheduledFuture<V> schedule(Callable<V> callable,long delay,TimeUnit unit){if(shutdown)throw new RejectedExecutionException();Task<V> task=new Task<>(callable,unit.toMillis(delay));tasks.add(task);return task;}
        public ScheduledFuture<?> schedule(Runnable runnable,long delay,TimeUnit unit){return schedule(()->{runnable.run();return null;},delay,unit);}
        public void execute(Runnable runnable){schedule(runnable,0,TimeUnit.MILLISECONDS);}
        void advance(long millis){now+=millis;while(true){Task<?> ready=tasks.stream().filter(task->!task.isCancelled()&&task.due<=now).min(Comparator.comparingLong((Task<?> task)->task.due).thenComparingLong(task->task.order)).orElse(null);if(ready==null)break;tasks.remove(ready);ready.run();try{ready.get();}catch(ExecutionException error){throw new AssertionError(error.getCause());}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}tasks.removeIf(Task::isCancelled);}
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable r,long a,long b,TimeUnit u){throw new UnsupportedOperationException();}public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r,long a,long b,TimeUnit u){throw new UnsupportedOperationException();}
        public void shutdown(){shutdown=true;}public List<Runnable> shutdownNow(){shutdown=true;tasks.forEach(task->task.cancel(false));tasks.clear();return List.of();}public boolean isShutdown(){return shutdown;}public boolean isTerminated(){return shutdown&&tasks.isEmpty();}public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
}
