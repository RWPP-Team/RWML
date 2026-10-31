/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.database.DataSetObserver;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.GameUi;

/**
 * 从游戏语言文件中读取游戏自身的文本。
 *
 * <p>布局无法调用引擎，因此需要翻译的标签会在引擎本地化键前加上 {@code i:} 标记，这也是游戏自身布局指定文本的方式。
 * 此处负责读取该标记：{@link #applyTo(View)} 遍历视图树并替换所有带标记的标签，{@link #get} 解析单个键，
 * {@link #setName} 为 Activity 的单个视图设置名称，供通过代码创建按钮的界面使用。</p>
 *
 * <p>语言文件中没有对应值时会保留布局中的原始标签；如果语言文件完全无法读取，则所有标签都保持原样。游戏原生界面也是
 * 这样处理的，不值得因此让整个界面加载失败。</p>
 */
public final class LocalizedText {

    private static final String TAG = "RWML";

    /** 游戏布局放在本地化键前面的标记。 */
    private static final String MARKER = "i:";

    private LocalizedText() {
    }

    /** RWML 自身的标签使用共享资源包，该资源包在引擎启动前也可用。 */
    public static String ui(String key, Object... arguments) {
        return Rwml.get() == null ? Messages.fallback(key, arguments)
            : Rwml.get().ui().messages().text(key, arguments);
    }

    /** 布局附加时调用，早于界面将玩家提供的文本绑定到布局。 */
    static void applyLabels(View root) {
        if (root instanceof TextView && !(root instanceof EditText) && Rwml.get() != null) {
            TextView label = (TextView) root;
            label.setText(Rwml.get().ui().messages().label(label.getText().toString()));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) { applyLabels(group.getChildAt(index)); }
        }
        applyTo(root);
    }

    /**
     * 获取引擎中本地化键对应的文本。
     *
     * @return 对应文本；语言文件中没有该键时返回 {@code null}
     */
    public static String get(String key) {
        if (key == null) {
            return null;
        }
        try {
            GameUi game = Rwml.get() == null ? null : Rwml.get().ui().find(GameUi.class);
            String value = game == null ? null : game.text(key);
            return value == null || value.isEmpty() ? null : value;
        } catch (Throwable notInTheLanguageFile) {
            // 语言文件中没有该键是正常情况，不视为错误：保留布局中的文本，与游戏原生界面一致。此处只记录调试信息，
            // 不作为警告；也不会输出异常，因为引擎会通过抛出异常表示缺少条目，在这里打印堆栈无法说明具体原因。
            Log.d(TAG, "The language file has no word for '" + key + "'.");
            return null;
        }
    }

    /** 带标记标签对应的引擎文本；标签未带标记时返回 {@code null}。 */
    public static String resolve(CharSequence marked) {
        if (!isMarked(marked)) {
            return null;
        }
        return get(marked.toString().substring(MARKER.length()));
    }

    /**
     * 将 {@code root} 下所有带标记的标签替换为引擎中的文本。
     *
     * <p>输入框提示也会一并解析，因为布局也用它来显示本地化文本。</p>
     */
    public static void applyTo(View root) {
        if (root == null) {
            return;
        }
        if (root instanceof Spinner) {
            Spinner spinner = (Spinner) root;
            if (spinner.getPrompt() != null && Rwml.get() != null) {
                spinner.setPrompt(Rwml.get().ui().messages().label(spinner.getPrompt().toString()));
            }
            SpinnerAdapter adapter = spinner.getAdapter();
            if (adapter != null && !(adapter instanceof TranslatedChoices)) {
                int selected = spinner.getSelectedItemPosition();
                spinner.setAdapter(new TranslatedChoices(adapter));
                if (selected >= 0 && selected < adapter.getCount()) { spinner.setSelection(selected); }
            }
        }
        if (root instanceof TextView) {
            TextView label = (TextView) root;
            String resolved = resolve(label.getText());
            if (resolved != null) {
                label.setText(resolved);
            }
            String hint = resolve(label.getHint());
            if (hint != null) {
                label.setHint(hint);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTo(group.getChildAt(i));
            }
        }
    }

    /**
     * 获取引擎中本地化键对应的文本；语言文件中没有该键时使用备用文本。
     *
     * <p>不属于布局的标签没有可保留的原始文本，因此无法读取本地化键时必须提供可读内容。备用文本取自游戏自身英文语言文件
     * 中该键对应的值。</p>
     */
    public static String word(String key, String fallback) {
        String value = get(key);
        return value == null ? fallback : value;
    }

    /** 使用引擎文本为布局视图命名；无法读取时保留布局原有文本。 */
    public static void setName(Activity owner, int id, String key) {
        View view = owner.findViewById(id);
        if (!(view instanceof TextView)) {
            return;
        }
        String value = get(key);
        if (value != null) {
            ((TextView) view).setText(value);
        }
    }

    private static boolean isMarked(CharSequence text) {
        return text != null
            && text.length() > MARKER.length()
            && text.charAt(0) == 'i'
            && text.charAt(1) == ':';
    }

    /** 翻译显示给用户的选项；getItem 仍返回游戏命令使用的原始值。 */
    private static final class TranslatedChoices extends BaseAdapter {
        private final SpinnerAdapter original;

        TranslatedChoices(SpinnerAdapter original) {
            this.original = original;
            original.registerDataSetObserver(new DataSetObserver() {
                @Override public void onChanged() { notifyDataSetChanged(); }
                @Override public void onInvalidated() { notifyDataSetInvalidated(); }
            });
        }

        @Override public int getCount() { return original.getCount(); }
        @Override public Object getItem(int position) { return original.getItem(position); }
        @Override public long getItemId(int position) { return original.getItemId(position); }
        @Override public boolean hasStableIds() { return original.hasStableIds(); }
        @Override public int getItemViewType(int position) { return original.getItemViewType(position); }
        @Override public int getViewTypeCount() { return original.getViewTypeCount(); }
        @Override public boolean isEnabled(int position) {
            return !(original instanceof ListAdapter) || ((ListAdapter) original).isEnabled(position);
        }
        @Override public boolean areAllItemsEnabled() {
            return !(original instanceof ListAdapter) || ((ListAdapter) original).areAllItemsEnabled();
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            View view = original.getView(position, recycled, parent);
            applyLabels(view);
            return view;
        }
        @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
            View view = original.getDropDownView(position, recycled, parent);
            applyLabels(view);
            return view;
        }
    }
}
