package com.muoniumplayer.configskija.host.library;

import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** Uses JDK native FileDialog; no second native library and no Minecraft input API. */
public final class DesktopLibraryFiles implements LibraryFiles {
    public boolean canChoose(){return !GraphicsEnvironment.isHeadless();}
    public boolean canOpenFolder(){return !GraphicsEnvironment.isHeadless()&&Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.OPEN);}
    public Path choosePreset(Path directory)throws Exception{return choose("导入模板预设",directory,"*.mpreset.json",FileDialog.LOAD);}
    public Path chooseProfileExport(Path directory,String filename)throws Exception{return choose("Export profile",directory,filename,FileDialog.SAVE);}
    private Path choose(String title,Path directory,String filename,int mode)throws Exception{
        if(EventQueue.isDispatchThread())throw new IllegalStateException("文件选择器必须从库worker发起");
        AtomicReference<Path> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        EventQueue.invokeAndWait(()->{try{FileDialog dialog=new FileDialog((Frame)null,title,mode);try{dialog.setDirectory(directory.toString());dialog.setFile(filename);dialog.setMultipleMode(false);dialog.setVisible(true);if(dialog.getFile()!=null)result.set(Path.of(dialog.getDirectory(),dialog.getFile()));}finally{dialog.dispose();}}catch(Throwable e){failure.set(e);}});
        if(failure.get()!=null)throw new java.io.IOException("系统文件选择器不可用",failure.get());return result.get();
    }
    public void openFolder(Path directory)throws Exception{Files.createDirectories(directory);if(!canOpenFolder())throw new IllegalStateException("系统不支持打开文件夹");Desktop.getDesktop().open(directory.toFile());}
}
