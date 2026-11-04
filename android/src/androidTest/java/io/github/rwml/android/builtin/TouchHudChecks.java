package io.github.rwml.android.builtin;

import android.graphics.Bitmap;
import android.os.Bundle;
import com.corrodinggames.rts.game.units.Unit;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeInterface;
import com.corrodinggames.rts.game.units.actions.SpecialAction;
import com.corrodinggames.rts.game.units.buildings.Factory;
import com.corrodinggames.rts.game.units.land.ExperimentalHoverTank;
import com.corrodinggames.rts.gameFramework.GameEngine;
import java.util.*;
import static io.github.rwml.android.builtin.NativeHandoffRunner.await;

/** Verifies native queue commands and offscreen thumbnail pixels, without device screenshots. */
final class TouchHudChecks {
    interface GameTask { void run(Runnable task) throws Exception; }
    static void verify(ClassLoader loader,GameEngine engine,Bundle result,GameTask game) throws Exception {
        Factory[] factory=new Factory[1];
        SpecialAction[] action=new SpecialAction[1];
        List<Unit> original=new ArrayList<>();
        Object[] productionSelection=new Object[1];
        double credits=engine.playerTeam.credits;
        try {
            game.run(() -> {
                try {
                    original.addAll(engine.interfaceEngine.selectedUnits);
                    factory[0]=(Factory)UnitType.landFactory.createUnit(false);
                    factory[0].setTeamOnCreate(engine.playerTeam);
                    factory[0].x=engine.cameraRenderX+250;factory[0].y=engine.cameraRenderY+250;
                    engine.playerTeam.credits=100000;
                    engine.interfaceEngine.clearUnitSelection(); engine.interfaceEngine.selectUnit(factory[0]);
                    Class<?> actions=loader.loadClass("io.github.rwpp.ui.NativeHudActions");
                    List<?> choices=(List<?>)actions.getMethod("selection",Unit.class,List.class)
                        .invoke(actions.getField("INSTANCE").get(null),factory[0],Collections.singletonList(factory[0]));
                    for(Object value:choices) {
                        SpecialAction candidate=(SpecialAction)value;
                        if(candidate.getUnitType()!=null && candidate.getCountForUnit(factory[0],false)>=0) { action[0]=candidate;break; }
                    }
                    if(action[0]==null) throw new AssertionError("No native unit-production queue action");
                    Class<?> touch=loader.loadClass("io.github.rwpp.ui.TouchQueuePanelKt");
                    if(!(boolean)touch.getMethod("hudUsesTouchQueuePanel",boolean.class,action[0].getActionType().getClass(),boolean.class,boolean.class)
                        .invoke(null,true,action[0].getActionType(),action[0].hasPersistentTooltip(),true))
                        throw new AssertionError("Native Android production action did not select the touch panel route");
                    Class<?> selectionType=loader.loadClass("io.github.rwpp.ui.HudProductionSelection");
                    productionSelection[0]=selectionType.getConstructor().newInstance();
                    java.lang.reflect.Method tap=selectionType.getMethod("tap",String.class);
                    if((boolean)tap.invoke(productionSelection[0],"test-factory:production")) throw new AssertionError("First click must select production only");
                    for(int click=0;click<3;click++) {
                        if(!(boolean)tap.invoke(productionSelection[0],"test-factory:production")) throw new AssertionError("Every later production click must build");
                        execute(loader,action[0],factory[0],false);
                    }
                } catch(Exception failure) { throw new RuntimeException(failure); }
            });
            await(() -> action[0].getCountForUnit(factory[0],true)==3,"three continuous production clicks, each queues one unit");
            game.run(() -> { try {
                engine.playerTeam.credits=0;
                for(int click=0;click<3;click++) execute(loader,action[0],factory[0],true);
                productionSelection[0].getClass().getMethod("clear").invoke(productionSelection[0]);
                if((boolean)productionSelection[0].getClass().getMethod("tap",String.class).invoke(productionSelection[0],"test-factory:production"))
                    throw new AssertionError("Dismissed production must select again before building");
            } catch(Exception failure) { throw new RuntimeException(failure); } });
            await(() -> action[0].getCountForUnit(factory[0],true)==0,"native HUD cancellation without add funds");
            result.putString("touchQueue","TOUCH_QUEUE_PASS: first click selects, every later production click queues one (3 consecutive additions); three cancellations work with zero credits; dismissal resets selection");
            game.run(() -> { try { preview(loader,engine,action[0],factory[0],result); SpecialUnitIconChecks.verify(loader,engine,factory[0],result); }
                catch(Exception failure) { throw new RuntimeException(failure); } });
        } finally {
            game.run(() -> {
                engine.playerTeam.credits=credits;
                engine.interfaceEngine.clearUnitSelection();
                if(factory[0]!=null) factory[0].destroy();
                for(Unit unit:original) if(!unit.dead && !unit.removed) engine.interfaceEngine.selectUnit(unit);
            });
        }
    }
    private static void execute(ClassLoader loader,SpecialAction action,Unit unit,boolean remove) throws Exception {
        loader.loadClass("io.github.rwpp.ui.HudGameActionsKt").getMethod("executeHudAction",SpecialAction.class,List.class,boolean.class)
            .invoke(null,action,Collections.singletonList(unit),remove);
    }
    private static void preview(ClassLoader loader,GameEngine engine,SpecialAction action,Unit target,Bundle result) throws Exception {
        ExperimentalHoverTank placeholder=(ExperimentalHoverTank)Unit.getPlaceholderOrDefault(UnitType.experimentalHoverTank);
        Bitmap shield=placeholder.getShieldImage().getBitmap();
        int density=shield.getDensity();float amount=placeholder.shield;float cooldown=placeholder.shieldCooldown;
        boolean shadows=engine.settings.renderExtraShadows;
        java.lang.reflect.Method render=loader.loadClass("io.github.rwpp.ui.HudImages_androidKt")
            .getMethod("nativeUnitActionIcon",SpecialAction.class,Unit.class,UnitTypeInterface.class,Unit.class);
        java.lang.reflect.Method bitmap=loader.loadClass("androidx.compose.ui.graphics.AndroidImageBitmap_androidKt")
            .getMethod("asAndroidBitmap",loader.loadClass("androidx.compose.ui.graphics.ImageBitmap"));
        try {
            // Force a density mismatch. Native source-rect bodies and bitmap(x,y) shields must
            // still be centred together in the production thumbnail renderer.
            shield.setDensity(Math.max(1,engine.context.getResources().getDisplayMetrics().densityDpi/2));
            placeholder.shield=0;placeholder.shieldCooldown=0;
            Bitmap base=(Bitmap)bitmap.invoke(null,render.invoke(null,action,target,UnitType.experimentalHoverTank,null));
            placeholder.shield=placeholder.maxShield;
            Bitmap full=(Bitmap)bitmap.invoke(null,render.invoke(null,action,target,UnitType.experimentalHoverTank,null));
            int count=0,minX=96,minY=96,maxX=-1,maxY=-1;
            for(int y=0;y<96;y++) for(int x=0;x<96;x++) if(base.getPixel(x,y)!=full.getPixel(x,y)) {
                count++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            }
            if(count<8 || Math.abs((minX+maxX)/2f-48f)>8 || Math.abs((minY+maxY)/2f-44f)>8)
                throw new AssertionError("Shield preview is missing or displaced: changed="+count+" bounds="+minX+","+minY+","+maxX+","+maxY);
            if(engine.settings.renderExtraShadows!=shadows) throw new AssertionError("Preview did not restore native shadow setting");
            result.putString("thumbnail","SHIELD_PREVIEW_PASS: real experimental-hover shield stays centred under forced bitmap-density mismatch");
        } finally { shield.setDensity(density);placeholder.shield=amount;placeholder.shieldCooldown=cooldown; }
    }
}
