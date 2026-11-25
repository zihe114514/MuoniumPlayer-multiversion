package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.ArrayList;
import java.util.List;

/** Shared measurements from the source Chips, ModCard and Profiles composables. */
final class LibraryPaint {
    static final double GAP = 19, CHIP_HEIGHT = 33;
    static final int DANGER = 0xFFE35B5B;
    private static final java.util.Set<String> MINECRAFT_ICONS=java.util.Set.of("cog","alert-circle","align","align-center","align-left","align-right","bell","bold","capitalized","check-circle","clipboard","close","cloud","combat","console","copy","copy-plus","dots-grid","dots-vertical","down","external-link","eye","eye-off","hud","info","italic","keyboard","layers","left-arrow","lightning-01","lock","lowercase","maximise","minus","moon","move","paintbrush","plus","preferences","profiles","qol","refresh","right-arrow","scissors","search","select-all","settings","shuffle","spanner","star","star-filled","strikethrough","sun","text","text-input","tick","trash","underline","unlock","up","uppercase","x-circle");
    private LibraryPaint() {}
    static boolean hasIcon(String name){return name!=null&&!name.isBlank()&&!name.equals("add");}
    static void drawIcon(PageContext c,String name,double x,double y,double size,int color){if(!hasIcon(name))return;String path=!c.theme().rounded()&&MINECRAFT_ICONS.contains(name)?"ico/minecraft/"+name+".svg":name;c.painter().icon(path,x,y,size,color); }
    static double chipWidth(PageContext c, String title, String icon) {
        return c.painter().measure(title, 14, false) + 24 + (!hasIcon(icon) ? 0 : 28);
    }
    static double chip(PageContext c, String id, String title, String icon, double x, double y,
                       boolean selected, boolean enabled, Runnable action, String tooltip) {
        double width = chipWidth(c, title, icon);
        button(c, id, title, icon, new Rect(x, y, width, CHIP_HEIGHT), selected, enabled, action, tooltip);
        return width;
    }
    static void button(PageContext c, String id, String title, String icon, Rect r, boolean selected,
                       boolean enabled, Runnable action, String tooltip) {
        Painter p = c.painter(); Theme t = c.theme();
        double hover = LibraryMotion.animate(c,id + ".hover", enabled && r.contains(c.mouseX(), c.mouseY()) ? 1 : 0, .2, Motion.Easing.SPRING);
        double selection=LibraryMotion.spring(c,id+".selected",selected?1:0,1500,.01);
        int hoverTint=Theme.mix(t.chip(),c.accent(),.5*hover),bg=Theme.mix(hoverTint,c.accent(),selection);
        p.rect(r,t.radius(8),enabled?bg:Theme.alpha(bg,.35));p.outline(r,t.radius(8),1,t.border());
        int color=Theme.mix(t.text(),t.accentText(),selection);if(!enabled)color=Theme.alpha(t.secondary(),.65);
        double tx = r.x() + 12;
        if (hasIcon(icon)) { drawIcon(c,icon, tx, r.y() + (r.h() - 18) / 2, 18, color); tx += 28; }
        p.textCenteredY(title, tx, r.y() + r.h() / 2, 14, false, color);
        if (enabled) c.hit(id, r, action, tooltip);
        else if (tooltip != null && !tooltip.isEmpty()) c.tooltip(id, r, tooltip);
    }
    static void icon(PageContext c, String id, String icon, Rect r, int color, boolean enabled, Runnable action, String tooltip) {
        double hover = LibraryMotion.animate(c,id + ".hover", enabled && r.contains(c.mouseX(), c.mouseY()) ? 1 : 0, .12, Motion.Easing.FAST_OUT_SLOW_IN);
        if (hover > .001) c.painter().rect(r, c.theme().radius(8), Theme.alpha(c.theme().text(), .08 * hover));
        drawIcon(c,icon, r.x() + (r.w() - 18) / 2, r.y() + (r.h() - 18) / 2, 18,
                enabled ? color : Theme.alpha(color, .35));
        if (enabled) c.contentHit(id, r, action, tooltip);
        else c.tooltip(id, r, tooltip);
    }
    /** Horizontal centering only; y remains paragraph line-top for existing callers. */
    static void center(PageContext c, String text, double cx, double y, double size, boolean bold, int color) {
        c.painter().text(text, cx - c.painter().measure(text, size, bold) / 2, y, size, bold, color);
    }
    static void card(PageContext c, Rect r, int border) {
        c.painter().rect(r, c.theme().radius(16), c.theme().card());
        c.painter().save();
        try { c.painter().clip(r, c.theme().radius(16));
            // Drawing narrow horizontal bands preserves the rounded outline while fading its alpha.
            for (int n=0;n<32;n++) {
                double y=r.y()+r.h()*n/32,h=r.h()/32;
                int fade=Theme.alpha(border,((border>>>24)/255.0)*(1-(n+.5)/32));
                c.painter().save();try { c.painter().clip(new Rect(r.x(),y,r.w(),h),0);c.painter().outline(r,c.theme().radius(16),1,fade); }
                finally { c.painter().restore(); }
            }
        } finally { c.painter().restore(); }
    }

    static String ellipsis(Painter p, String value, double max, double size, boolean bold) {
        if (max <= 0) return "";
        if (p.measure(value, size, bold) <= max) return value;
        int end = value.length();
        while (end > 0 && p.measure(value.substring(0,end) + "…", size, bold) > max) end = value.offsetByCodePoints(end,-1);
        return value.substring(0,end) + "…";
    }
    static List<String> wrap(Painter p, String value, double max, double size) {
        List<String> lines = new ArrayList<>(); StringBuilder line = new StringBuilder();
        for (int i=0; i<value.length();) {
            int cp=value.codePointAt(i); i+=Character.charCount(cp);
            if (cp=='\n') { lines.add(line.toString()); line.setLength(0); continue; }
            String ch=new String(Character.toChars(cp));
            if (line.length()>0 && p.measure(line+ch,size,false)>max) { lines.add(line.toString()); line.setLength(0); }
            line.append(ch);
        }
        if (line.length()>0 || lines.isEmpty()) lines.add(line.toString()); return lines;
    }
    static double paragraph(PageContext c,String value,double x,double y,double max,double size,int color) {
        for(String line:wrap(c.painter(),value,max,size)) { c.painter().text(line,x,y,size,false,color); y+=size*1.45; } return y;
    }
    static void empty(PageContext c, String text, Rect r) { c.painter().textCentered(text,r,14,false,c.theme().secondary()); }
    static void status(PageContext c, String message, Rect r) {
        if(message!=null&&!message.isBlank()) c.painter().text(ellipsis(c.painter(),message,r.w(),12,false),r.x(),r.y(),12,false,c.theme().secondary());
    }
    static boolean run(PageContext c,String action,Runnable task) {
        try { task.run(); return true; } catch(RuntimeException failure) { c.host().error(action,failure); return false; }
    }
}
