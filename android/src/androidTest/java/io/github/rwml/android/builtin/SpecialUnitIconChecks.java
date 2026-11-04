package io.github.rwml.android.builtin;

import android.os.Bundle;
import com.corrodinggames.rts.game.units.Unit;
import com.corrodinggames.rts.game.units.UnitType;
import com.corrodinggames.rts.game.units.UnitTypeInterface;
import com.corrodinggames.rts.game.units.actions.SpecialAction;
import com.corrodinggames.rts.game.units.actions.SpecialActionBuild;
import com.corrodinggames.rts.gameFramework.GameEngine;
import java.lang.reflect.Method;
import java.util.*;

/** Read actual extension thumbnails on the native game thread, including generated limb shadows. */
final class SpecialUnitIconChecks {
    static void verify(ClassLoader loader,GameEngine engine,Unit target,Bundle result) throws Exception {
        Class<?> images=loader.loadClass("io.github.rwpp.ui.ActionImages");
        Method load=images.getMethod("load",SpecialAction.class,Unit.class);
        Class<?> imageType=loader.loadClass("androidx.compose.ui.graphics.ImageBitmap");
        Method read=imageType.getMethod("readPixels",int[].class,int.class,int.class,int.class,int.class,int.class,int.class);
        Set<String> expected=new HashSet<>(Arrays.asList("amphibiousJet","experimentalSpider","modularSpider","experimentalHoverTank"));
        List<String> pixelsByType=new ArrayList<>();
        for(Object entry:UnitType.allTypes) {
            UnitTypeInterface type=(UnitTypeInterface)entry;
            String id=type.getActionIdName();
            if(!expected.contains(id)) continue;
            Object bitmap=load.invoke(images.getField("INSTANCE").get(null),new SpecialActionBuild(type),target);
            if(bitmap==null) throw new AssertionError("Empty real production icon: "+id);
            int w=(int)imageType.getMethod("getWidth").invoke(bitmap),h=(int)imageType.getMethod("getHeight").invoke(bitmap);
            int[] pixels=new int[w*h];read.invoke(bitmap,pixels,0,0,w,h,0,w);
            long visible=Arrays.stream(pixels).filter(c->(c>>>24)>32 && (c&0x00ffffff)>0x050505).count();
            if(visible<20) throw new AssertionError("Invisible real production icon: "+id+" pixels="+visible);
            pixelsByType.add(id+"="+visible);expected.remove(id);
        }
        if(!expected.isEmpty()) throw new AssertionError("Required native types absent: "+expected);
        result.putString("specialUnitIcons","SPECIAL_UNIT_ICONS_PASS: "+String.join(", ",pixelsByType));
    }
}
