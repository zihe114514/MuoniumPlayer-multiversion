package com.muoniumplayer.configskija.library;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.pages.*;
import io.github.humbleui.skija.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** Fixture-only raster verification with the host's exact Skija dependencies and font files. */
public final class LibraryRasterPreview {
    public static void main(String[] args)throws Exception{
        Path music=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        try(Typeface regular=FontMgr.getDefault().makeFromFile(music.resolve("muonium-common/src/main/resources/muonium/fonts/pf_normal.ttf").toString());
            Typeface bold=FontMgr.getDefault().makeFromFile(music.resolve("muonium-common/src/main/resources/muonium/fonts/pf_middleblack.ttf").toString())){
            String[] themeIds={"polyglass-dark","polyglass-light","minecraft-dark","minecraft-light"};
            if(regular==null||bold==null)throw new IllegalStateException("Host font missing");
            String[] names={"mods","profiles","keys","credits","template-detail","template-save","template-import","profile-create"};
            for(String themeId:themeIds)for(String name:names){
                LibraryParityTest.FakeHost host=new LibraryParityTest.FakeHost();LibraryTestContext fixture=new LibraryTestContext(host);fixture.prefs.set("theme",themeId);
                UiPage page=switch(name){case "mods"->LibraryPages.mods();case "keys"->LibraryPages.keys();case "credits"->LibraryPages.credits();default->LibraryPages.profiles();};
                fixture.frame(page);
                switch(name){case "template-detail"->fixture.click("templates.card.builtin");case "template-save"->fixture.click("templates.action.0");case "template-import"->fixture.click("templates.action.1");case "profile-create"->fixture.click("profiles.create");}
                try(Surface surface=Surface.makeRasterN32Premul(1391,700);Paint ink=new Paint().setAntiAlias(true)){
                    Canvas canvas=surface.getCanvas();canvas.clear(themeId.endsWith("light")?0xFFE8EDFF:0xFF11171C);
                    Map<String,Font> fonts=new HashMap<>();
                    try(SkijaPainter painter=new SkijaPainter(()->canvas,new SkijaPainter.Text(){
                        Font font(double size,boolean heavy){return fonts.computeIfAbsent(size+":"+heavy,k->new Font(heavy?bold:regular,(float)size));}
                        public void draw(String text,double x,double y,double size,boolean heavy,int color){Font font=font(size,heavy);canvas.drawString(text,(float)x,(float)(y-font.getMetrics().getAscent()),font,ink.setColor(color));}
                        public double measure(String text,double size,boolean heavy){return font(size,heavy).measureTextWidth(text);}
                    },SkijaPainter.loadIcons())){
                        PageContext context=(PageContext)Proxy.newProxyInstance(LibraryRasterPreview.class.getClassLoader(),new Class<?>[]{PageContext.class},(proxy,method,values)->{
                            if(method.getName().equals("painter"))return painter;
                            if(method.getName().equals("theme"))return Theme.byId(themeId);
                            if(method.getName().equals("textField")){
                                TextEdit edit=(TextEdit)values[1];Geometry.Rect bounds=(Geometry.Rect)values[2];Theme theme=Theme.byId(themeId);
                                painter.rect(bounds,theme.radius(8),theme.card());painter.outline(bounds,theme.radius(8),1,edit.focused?fixture.accent():theme.border());
                                painter.text(edit.text().isEmpty()?(String)values[3]:edit.text(),bounds.x()+12,bounds.y()+8,13,false,edit.text().isEmpty()?theme.secondary():theme.text());
                            }
                            return method.invoke(fixture,values);
                        });
                        page.render(context);
                        painter.text("Library fixture · "+name,289,26,13,false,0xFF757883);
                        try(Image image=surface.makeImageSnapshot();Data png=image.encodeToData(EncodedImageFormat.PNG)){if(png==null)throw new AssertionError("PNG encoding");Files.write(output.resolve(themeId+"-"+name+".png"),png.getBytes());}
                    }finally{fonts.values().forEach(Font::close);}
                }
            }
        }
        System.out.println("LibraryRasterPreview: 32 Skija fixture previews written to "+output);
    }
}
