package io.github.rwml.android.builtin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.*;

/** Exposes only a generated test archive at an opaque URI. No device files are exposed. */
public final class ImportFixtureProvider extends ContentProvider {
    public static final String ID="io.github.rwml.validation.systemimport";
    public static final Uri URI=Uri.parse("content://io.github.rwml.android.test.archive/opaque/42");
    public static final Uri MOD_URI=Uri.parse("content://io.github.rwml.android.test.archive/opaque/mod");
    public static final Uri MAP_URI=Uri.parse("content://io.github.rwml.android.test.archive/opaque/map");
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        if(MOD_URI.equals(uri)) return "application/x-rwmod";
        if(MAP_URI.equals(uri)) return "application/xml";
        return "application/java-archive";
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{MOD_URI.equals(uri) ? "../provider-import.rwmod"
            : MAP_URI.equals(uri) ? "maps\\provider-import.tmx" : "provider-import.jar"}); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!(URI.equals(uri)||MOD_URI.equals(uri)||MAP_URI.equals(uri)) || !"r".equals(mode))
            throw new FileNotFoundException("Unknown read-only fixture");
        try {
            byte[] bytes;
            if(MOD_URI.equals(uri)) bytes="test rwmod payload".getBytes(StandardCharsets.UTF_8);
            else if(MAP_URI.equals(uri)) bytes="<map/>".getBytes(StandardCharsets.UTF_8);
            else try(InputStream input=getContext().getAssets().open("import-fixture.jar")) {
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
                bytes=output.toByteArray();
            }
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            new Thread(() -> { try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(bytes);
            } catch(IOException ignored) {} },"import-fixture-stream").start();
            return pipe[0];
        } catch(IOException failure) { throw new FileNotFoundException(failure.toString()); }
    }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
