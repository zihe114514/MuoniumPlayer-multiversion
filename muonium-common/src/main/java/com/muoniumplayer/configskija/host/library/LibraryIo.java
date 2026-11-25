package com.muoniumplayer.configskija.host.library;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Blocking helpers. Call only on the library worker, never from a render/input callback. */
public final class LibraryIo {
    public static final long MAX_PROFILE_BYTES=4L*1024*1024;
    private LibraryIo(){}
    public static Properties read(Path path,long limit)throws IOException{
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("文件不存在或不是普通文件");
        if(Files.size(path)>limit)throw new IOException("文件过大");Properties properties=new Properties();
        try(InputStream stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=stream.readNBytes((int)Math.min(Integer.MAX_VALUE,limit+1));if(bytes.length>limit)throw new IOException("文件过大");try(Reader reader=new StringReader(new String(bytes,StandardCharsets.UTF_8))){properties.load(reader);}}
        return properties;
    }
    public static byte[] bytes(Properties properties)throws IOException{StringWriter writer=new StringWriter();properties.store(writer,"MuoniumPlayer trusted local profile; not a template/share code");return writer.toString().getBytes(StandardCharsets.UTF_8);}
    public static void write(Path path,Properties properties,BooleanSupplier valid)throws IOException{byte[] frozen=bytes(properties);if(frozen.length>MAX_PROFILE_BYTES)throw new IOException("配置快照超过本地档案大小上限");atomic(path,frozen,valid);}
    public static void atomic(Path path,byte[] bytes,BooleanSupplier valid)throws IOException{
        Path target=path.toAbsolutePath().normalize(),parent=target.getParent();if(parent==null)throw new IOException("缺少目标目录");Files.createDirectories(parent);
        if(Files.isSymbolicLink(target))throw new IOException("拒绝覆盖符号链接");Path temporary=Files.createTempFile(parent,"library-",".tmp");
        try{Files.write(temporary,bytes);if(!valid.getAsBoolean())throw new java.util.concurrent.CancellationException("操作已过期");
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    public static void export(Path destination,Properties profile,BooleanSupplier valid)throws IOException{
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(buffer,StandardCharsets.UTF_8)){
            zip.putNextEntry(new ZipEntry("profile.properties"));zip.write(bytes(profile));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));zip.write(("MuoniumPlayer LOCAL profile archive, format 1.\nContains the selected local music state, UI preferences and supported controls.\nThis is not a shareable template. Other mods' configuration directories are not included.\n").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }atomic(destination,buffer.toByteArray(),valid);
    }
    public static Properties copy(Properties original){Properties out=new Properties();original.forEach(out::put);return out;}
    public static Throwable unwrap(Throwable error){while((error instanceof java.util.concurrent.CompletionException||error instanceof java.util.concurrent.ExecutionException)&&error.getCause()!=null)error=error.getCause();return error;}
    public static String message(Throwable error){Throwable e=unwrap(error);return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
