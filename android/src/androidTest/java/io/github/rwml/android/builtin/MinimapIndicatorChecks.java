package io.github.rwml.android.builtin;

import android.os.Bundle;
import com.corrodinggames.rts.game.Projectile;
import com.corrodinggames.rts.game.units.Unit;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.gui.MinimapHandlerNotificationType;
import java.util.*;
import static io.github.rwml.android.builtin.NativeHandoffRunner.await;

/** Exercises native signal producers in a temporary local match without changing HP or saves. */
final class MinimapIndicatorChecks {
    interface Task { void run() throws Exception; }
    interface GameThread { void run(Task task) throws Exception; }
    private static Object signals;
    private static java.lang.reflect.Method snapshot;
    static void verify(ClassLoader loader,GameEngine engine,Object settings,Bundle result,GameThread game) throws Exception {
        Class<?> signalsType=loader.loadClass("io.github.rwpp.ui.AeroMinimapSignals");
        signals=signalsType.getField("INSTANCE").get(null); snapshot=signalsType.getMethod("snapshot");
        boolean hidden=(boolean)settings.getClass().getMethod("getHideOriginalHud").invoke(settings);
        Unit[] source=new Unit[1];Projectile[] projectile=new Projectile[1];
        List<Object> oldNotifications=new ArrayList<>(),oldRecordings=new ArrayList<>();
        int x=engine.map.mapWidthInTiles*engine.map.tileWidth-30;
        int y=engine.map.mapHeightInTiles*engine.map.tileHeight-30;
        if(engine.viewRectExact.contains(x,y)) { x=30;y=30; }
        final int signalX=x,signalY=y;
        try {
            settings.getClass().getMethod("setHideOriginalHud",boolean.class).invoke(settings,true);
            game.run(()->{
                oldNotifications.addAll(engine.minimap.damageNotifications);oldRecordings.addAll(engine.minimap.damageRecordings);
                engine.minimap.damageNotifications.clear();engine.minimap.damageRecordings.clear();
                source[0]=UnitType.tank.createUnit(false);source[0].setTeamOnCreate(engine.playerTeam);source[0].x=signalX;source[0].y=signalY;
                engine.minimap.recordDamage(signalX,signalY,16f,source[0]);
            });
            await(()->hasAlert("unit") && channel(border(),16)>0 && channel(border(),8)==0,"native damage recording to Aero red alert and border");
            game.run(()->{
                engine.minimap.damageNotifications.clear();engine.minimap.damageRecordings.clear();
                engine.minimap.addNotification(signalX,signalY,MinimapHandlerNotificationType.message);
            });
            await(()->hasAlert("message") && channel(border(),8)>0 && channel(border(),0)>0,"native trigger notification to Aero blue alert");
            game.run(()->{
                engine.minimap.damageNotifications.clear();
                projectile[0]=Projectile.createProjectile(source[0],signalX-300,signalY-300);
                projectile[0].targetUnit=source[0];projectile[0].nukeWeapon=true;
                projectile[0].lifeTimer=600f;projectile[0].speed=0f;projectile[0].areaDamage=0f;projectile[0].areaRadius=0f;
            });
            await(()->!markers().isEmpty() && channel(border(),8)>0 && channel(border(),16)==0,"native nuke projectile marker and green border");
            float angle=angle();Thread.sleep(100);
            if(angle()==angle) throw new AssertionError("Native minimap pulse angle did not animate");
            result.putString("minimapIndicators","MINIMAP_INDICATORS_PASS: native damage threshold/red border, trigger endpoint/blue border, harmless nuke marker/green border and advancing pulse");
        } finally {
            game.run(()->{
                if(projectile[0]!=null) projectile[0].destroy();
                if(source[0]!=null) source[0].destroy();
                engine.minimap.damageNotifications.clear();engine.minimap.damageNotifications.addAll(oldNotifications);
                engine.minimap.damageRecordings.clear();engine.minimap.damageRecordings.addAll(oldRecordings);
            });
            settings.getClass().getMethod("setHideOriginalHud",boolean.class).invoke(settings,hidden);
        }
    }
    private static Object current() throws Exception { return snapshot.invoke(signals); }
    private static boolean hasAlert(String type) throws Exception {
        Object value=current();
        for(Object alert:(List<?>)value.getClass().getMethod("getAlerts").invoke(value))
            if(type.equals(alert.getClass().getMethod("getType").invoke(alert).toString())) return true;
        return false;
    }
    private static int border() throws Exception { Object value=current();Object color=value.getClass().getMethod("getBorderFlashColor").invoke(value);return color==null?0:(Integer)color; }
    private static int channel(int color,int shift) { return color>>>shift&255; }
    private static List<?> markers() throws Exception { Object value=current();return (List<?>)value.getClass().getMethod("getProjectileMarkers").invoke(value); }
    private static float angle() throws Exception { Object value=current();return (Float)value.getClass().getMethod("getNotificationPulseAngle").invoke(value); }
}
