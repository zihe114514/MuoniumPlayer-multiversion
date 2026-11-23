package com.muoniumplayer.configskija.ui.pages;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.List;

/** Credits.kt transcription. Version strings are queried on each frame and never guessed. */
public final class CreditsPage implements UiPage {
    public record Section(String title,List<String> entries) {}
    public static List<Section> sections(String configVersion){
        return List.of(new Section("MuoniumPlayer",List.of("心事抱抱 — 项目所有者","云杉","N0ne","eyz2021","Chryui (Cherry7593)")),
                new Section("界面与配置",List.of(configVersion==null||configVersion.isBlank()?"MuoniumConfig — HUD 编辑器与设置界面":"MuoniumConfig v"+configVersion+" — HUD 编辑器与设置界面","基于 OneConfig v1 精简（LGPL-3.0 + Additional Terms）","OneConfig 原作者：Polyfrost 及其贡献者")),
                new Section("第三方组件",List.of("Compose Multiplatform — JetBrains","Skia / Skija — Google / JetBrains","Rhino — Mozilla","OpenAL Soft — 音频输出")));
    }
    public void render(PageContext c){
        Rect b=c.content();Painter p=c.painter();Theme t=c.theme();LibraryServices.Information info=c.host() instanceof LibraryServices.Information s?s:null;
        String player=info==null?"":info.playerVersion(),config=info==null?"":info.configVersion();double y=b.y()-c.scroll()+16;
        p.save();try{p.clip(b,0);
            p.save();try{p.translate(b.x(),y+7);p.scale(34.0/56,22.0/56);LibraryPaint.drawIcon(c,t.id().startsWith("minecraft")?(t.id().endsWith("light")?"brand/oneconfig-mc-light.svg":"brand/oneconfig-mc.svg"):(t.id().endsWith("light")?"brand/oneconfig-light.svg":"brand/oneconfig.svg"),0,0,56,c.accent());}finally{p.restore();}
            p.text("MuoniumPlayer",b.x()+44,y,26,true,t.text());
            if(player!=null&&!player.isBlank())p.text("v"+player.trim(),b.x()+52+p.measure("MuoniumPlayer",26,true),y+6,13,false,t.secondary());
            y+=26*1.45+8;p.text("MuoniumPlayer 的 HUD 编辑器与设置界面",b.x(),y,13,false,t.secondary());y+=13*1.45+4;
            for(Section section:sections(config)){y+=24;p.text(section.title(),b.x(),y,24,true,t.text());y+=24*1.45+8;for(String line:section.entries()){y=LibraryPaint.paragraph(c," - "+line,b.x(),y,b.w(),13,t.secondary())+4;}y-=4;}
            y+=24;y=LibraryPaint.paragraph(c,"MuoniumConfig 的界面代码来自 OneConfig，按 LGPL-3.0 与附加条款分发；完整许可见 mod 包内的 LICENSE 文件。",b.x(),y,b.w(),12,t.secondary())+4;
            p.text("https://melodifymc.cc.cd/",b.x(),y,12,false,t.secondary());y+=12*1.45+16;
        }finally{p.restore();}c.scrollRange(Math.max(0,y+c.scroll()-b.bottom()));
    }
}
