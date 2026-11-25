package com.muoniumplayer.configskija.migration;

import com.muoniumplayer.configskija.host.migration.LegacyMigration;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Prints counts/diagnostic codes only. No old values, profile names, file bytes or fingerprints. */
public final class MigrationInspectMain {
    public static void main(String[] args)throws Exception{
        LegacyMigration.Snapshot snapshot=LegacyMigration.read(new LegacyMigration.Request(Path.of(args[0]),1280,720,1));
        Map<String,Integer> codes=new TreeMap<>();snapshot.issues().forEach(issue->codes.merge(issue.code(),1,Integer::sum));
        System.out.println("Legacy migration read-only: preferences="+snapshot.preferences().size()+", HUDs="+snapshot.hud().elements().size()+", profiles="+snapshot.profiles().size()+", allowlistedFilesRead="+snapshot.filesRead());
        System.out.println("Diagnostic counts="+codes+"; client application=not-run; legacy writes=0; runtime fingerprints=0");
    }
}
