package br.com.nh.cotacao.service;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.zip.*;

/** Safely extracts one FIPE CSV/Parquet member without trusting ZIP paths or names. */
final class FipeUploadFiles {
    private FipeUploadFiles() {}

    static Path unpack(Path source,long maximumUncompressedBytes) throws IOException {
        String name=source.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!name.endsWith(".zip")) {
            if(name.endsWith(".parquet") || name.endsWith(".csv")) return source;
            throw new IOException("Extensão FIPE desconhecida");
        }
        Path output=null; int dataFiles=0;
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(entry.isDirectory()) {zip.closeEntry();continue;}
                String filename=entry.getName().toLowerCase(Locale.ROOT);
                if(filename.startsWith("__macosx/") || filename.endsWith(".ds_store")) {zip.closeEntry();continue;}
                if(!filename.endsWith(".parquet") && !filename.endsWith(".csv")) {
                    throw new IOException("ZIP FIPE deve conter apenas um CSV ou Parquet, sem arquivos adicionais");
                }
                if(++dataFiles>1) throw new IOException("ZIP FIPE possui vários datasets; envie apenas um");
                output=Files.createTempFile("nh-fipe-unpacked-",filename.endsWith(".parquet")?".parquet":".csv");
                try(OutputStream destination=Files.newOutputStream(output,StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer=new byte[65536];long count=0;int bytes;
                    while((bytes=zip.read(buffer))!=-1) {
                        count+=bytes;
                        if(count>maximumUncompressedBytes) throw new IOException("ZIP FIPE descompactado supera o limite permitido");
                        destination.write(buffer,0,bytes);
                    }
                }
                zip.closeEntry();
            }
        } catch(IOException failure) {
            if(output!=null) Files.deleteIfExists(output);
            throw failure;
        }
        if(output==null) throw new IOException("O ZIP não contém um arquivo CSV ou Parquet FIPE");
        return output;
    }

    static boolean isParquet(Path file) throws IOException {
        String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!name.endsWith(".parquet")) return false;
        long size=Files.size(file);
        if(size<8) throw new IOException("Arquivo Parquet inválido");
        byte[] magic=new byte[4],tail=new byte[4];
        try(var input=Files.newInputStream(file)) {
            if(input.read(magic)!=4) throw new IOException("Cabeçalho Parquet incompleto");
        }
        try(var fileReader=new RandomAccessFile(file.toFile(),"r")) {
            fileReader.seek(size-4); fileReader.readFully(tail);
        }
        if(!java.util.Arrays.equals(magic,new byte[]{'P','A','R','1'}) ||
           !java.util.Arrays.equals(tail,new byte[]{'P','A','R','1'}))
            throw new IOException("Parquet inválido (assinatura PAR1 ausente)");
        return true;
    }
}
