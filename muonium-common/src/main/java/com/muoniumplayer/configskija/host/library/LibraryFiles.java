package com.muoniumplayer.configskija.host.library;

import java.nio.file.Path;

/** OS calls run on the worker; implementations may marshal a native modal dialog to the AWT event queue. */
public interface LibraryFiles {
    boolean canChoose();
    boolean canOpenFolder();
    Path choosePreset(Path directory)throws Exception;
    Path chooseProfileExport(Path directory,String filename)throws Exception;
    void openFolder(Path directory)throws Exception;
}
