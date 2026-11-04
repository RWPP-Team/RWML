package io.github.rwml.android.builtin;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import io.github.rwml.android.ArchiveImports;
import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.android.EngineContext;
import io.github.rwml.android.MainActivity;
import io.github.rwml.api.extensions.ExtensionEntry;
import java.io.InputStream;
import static io.github.rwml.android.builtin.NativeHandoffRunner.await;

final class ArchiveImportChecks {
    static void verify(Instrumentation test,Bundle result) throws Exception {
        android.content.Context context=test.getTargetContext();
        Intent picker=ArchiveImports.pickerIntent();
        if(!Intent.ACTION_OPEN_DOCUMENT.equals(picker.getAction()) || picker.getComponent()!=null
            || !picker.hasCategory(Intent.CATEGORY_OPENABLE)
            || picker.resolveActivity(context.getPackageManager())==null)
            throw new AssertionError("Import must resolve to the system document picker");
        ArchiveImports.Request previous=ArchiveImports.pending(context);
        if(previous!=null) {
            if(!fixtureArchive(previous)) throw new AssertionError("A user import is pending; do not overwrite it for validation");
            ArchiveImports.discard(context,previous);
        }
        if(!ImportFixtureProvider.URI.equals(ArchiveImports.incomingUri(new Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM,ImportFixtureProvider.URI)))) throw new AssertionError("Share URI forwarding failed");
        ExtensionEntry imported=null;
        ArchiveImports.Request staged=null;
        try {
            // An actual external sender grants visibility and read access to its provider.
            // Starting from the recipient's Context cannot manufacture that provider grant.
            try(android.os.ParcelFileDescriptor command=test.getUiAutomation().executeShellCommand(
                    "am start -W --grant-read-uri-permission -a android.intent.action.VIEW -t application/java-archive -d "
                    + ImportFixtureProvider.URI + " -n io.github.rwml.android/.MainActivity");
                InputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {
                byte[] buffer=new byte[1024]; while(output.read(buffer)!=-1) { }
            }
            await(() -> ArchiveImports.pending(context)!=null,"external document staging");
            staged=ArchiveImports.pending(context);
            if(!"provider-import.jar".equals(staged.name)) throw new AssertionError("Provider display name was lost");
            await(() -> EngineContext.foregroundActivity() instanceof ExtensionManagerActivity,"open-with extension manager");
            Activity old=EngineContext.foregroundActivity();
            test.runOnMainSync(old::recreate);
            await(() -> EngineContext.foregroundActivity() instanceof ExtensionManagerActivity
                && EngineContext.foregroundActivity()!=old,"extension manager recreation");
            java.lang.reflect.Field dialog=ExtensionManagerActivity.class.getDeclaredField("importDialog");dialog.setAccessible(true);
            await(() -> { Object value=dialog.get(EngineContext.foregroundActivity());return value!=null&&((android.app.AlertDialog)value).isShowing(); },"import confirmation after recreation");
            if(!ArchiveImports.pending(context).file.equals(staged.file)) throw new AssertionError("Recreation lost or recopied staged import");
            try(InputStream input=staged.open()) { imported=AndroidBootstrap.get(context).ui().extensions().importArchive(input,false); }
            if(!ImportFixtureProvider.ID.equals(imported.id())) throw new AssertionError("Provider stream did not import into the extension library");
            result.putString("stream","SYSTEM_PICKER_PASS: ACTION_OPEN_DOCUMENT resolves outside RWML\nOPEN_WITH_IMPORT_PASS: opaque content URI, provider name, Activity recreation and actual extension import\n");
        } finally {
            if(imported!=null) AndroidBootstrap.get(context).ui().extensions().removeArchive(imported.source());
            if(staged!=null) ArchiveImports.discard(context,staged);
            Activity current=EngineContext.foregroundActivity();
            if(current instanceof ExtensionManagerActivity) test.runOnMainSync(current::finish);
        }
        verifyGameFileStaging(context,result);
        io.github.rwml.android.GameFileImportChecks.verify(context,result);
    }

    private static boolean fixtureArchive(ArchiveImports.Request request) {
        if(request.kind!=ArchiveImports.Kind.EXTENSION || !request.name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) return false;
        try(java.util.jar.JarFile archive=new java.util.jar.JarFile(request.file)) {
            java.util.jar.JarEntry entry=archive.getJarEntry("META-INF/extension.properties");
            if(entry==null) return false;
            java.util.Properties descriptor=new java.util.Properties();
            try(InputStream input=archive.getInputStream(entry)) { descriptor.load(input); }
            return ImportFixtureProvider.ID.equals(descriptor.getProperty("id"));
        } catch(Exception invalid) { return false; }
    }

    private static void verifyGameFileStaging(android.content.Context context,Bundle result) throws Exception {
        ArchiveImports.Request staged=null;
        try {
            if(!ImportFixtureProvider.MOD_URI.equals(ArchiveImports.incomingUri(
                    new Intent(Intent.ACTION_VIEW,ImportFixtureProvider.MOD_URI))))
                throw new AssertionError("VIEW did not forward the mod content URI");
            if(!ImportFixtureProvider.MAP_URI.equals(ArchiveImports.incomingUri(new Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_STREAM,ImportFixtureProvider.MAP_URI))))
                throw new AssertionError("SEND did not forward the map content URI");
            Intent clipShare=new Intent(Intent.ACTION_SEND);
            clipShare.setClipData(android.content.ClipData.newUri(context.getContentResolver(),"shared mod",ImportFixtureProvider.MOD_URI));
            if(!ImportFixtureProvider.MOD_URI.equals(ArchiveImports.incomingUri(clipShare)))
                throw new AssertionError("SEND did not forward a ClipData content URI");
            staged=ArchiveImports.stage(context,ImportFixtureProvider.MOD_URI);
            if(staged.kind!=ArchiveImports.Kind.MOD || staged.isGameFile()==false
                || !"provider-import.rwmod".equals(staged.name))
                throw new AssertionError("The .rwmod was not classified as a native game mod");
            ArchiveImports.Request duplicate=ArchiveImports.stage(context,ImportFixtureProvider.MOD_URI);
            if(!staged.id.equals(duplicate.id) || !staged.file.equals(duplicate.file))
                throw new AssertionError("Repeated intent duplicated or replaced the private snapshot");
            boolean blocked=false;
            try { ArchiveImports.stage(context,ImportFixtureProvider.MAP_URI); }
            catch(java.io.IOException expected) { blocked=true; }
            if(!blocked || !staged.file.equals(ArchiveImports.pending(context).file))
                throw new AssertionError("A different incoming file replaced a pending import");
            ArchiveImports.discard(context,staged); staged=null;

            staged=ArchiveImports.stage(context,ImportFixtureProvider.MAP_URI);
            if(staged.kind!=ArchiveImports.Kind.MAP || !"provider-import.tmx".equals(staged.name))
                throw new AssertionError("The .tmx was not classified as a native game map");
            try(InputStream input=staged.open()) {
                byte[] bytes=new byte[16]; int count=input.read(bytes);
                if(count<=0 || !"<map/>".equals(new String(bytes,0,count,java.nio.charset.StandardCharsets.UTF_8)))
                    throw new AssertionError("The map was not snapshotted from its ContentResolver stream");
            }
            result.putString("gameFileStaging","GAME_FILE_STAGING_PASS: .rwmod/.tmx routing, sanitized provider names, persistent snapshots, duplicate delivery and pending-file protection\n");
        } finally { if(staged!=null) ArchiveImports.discard(context,staged); }
    }
}
