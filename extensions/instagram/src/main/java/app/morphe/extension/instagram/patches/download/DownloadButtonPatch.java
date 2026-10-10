/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.download;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.downloader.MediaType;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import com.instagram.common.session.UserSession;

/**
 * Adds a one tap download button next to Instagram's own buttons:
 * beside share on feed posts, below save on reels and beside share on stories.
 * <p>
 * Instagram's views are found at runtime by resource name, so this does not depend on
 * obfuscated layout code. The media of a row is found when the button is tapped by walking
 * the object graph reachable from the surrounding views and their click listeners.
 */
@SuppressWarnings("unused")
public class DownloadButtonPatch {

    private enum Surface { FEED, REELS, STORY }

    private enum Axis { HORIZONTAL, VERTICAL }

    private static final String BUTTON_TAG = "piko_download_button";

    // Resource names of the button the download button is placed next to.
    private static final String[] FEED_ANCHORS = {"row_feed_button_share"};
    private static final String[] REELS_ANCHORS = {"clips_save_button", "clips_ufi_save_button", "ufi_save_button"};
    private static final String[] STORY_ANCHORS = {
            "toolbar_reshare_button", "toolbar_share_button", "reel_viewer_share_button", "toolbar_direct_share_button"
    };

    // Used when no anchor above exists in this Instagram version.
    private static final String FEED_FALLBACK = "row_feed_button_comment";
    private static final String STORY_FALLBACK = "toolbar_like_button";
    private static final String REELS_CONTAINER = "ufi_stack";
    // Generic names only accepted inside the reels button column.
    private static final String[] REELS_GENERIC_ANCHORS = {"save_button", "row_feed_button_save"};

    private static final String REEL_ITEM_CLASS = "com.instagram.model.reels.ReelItem";

    private static final int SCAN_DELAY_MS = 150;
    private static final int MAX_ANCESTORS = 14;
    private static final int MAX_SEARCH_DEPTH = 5;
    private static final int MAX_SEARCH_OBJECTS = 1500;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<View> ATTACHED_ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> PENDING_ROOTS = Collections.newSetFromMap(new WeakHashMap<>());

    private static Map<Integer, Surface> anchorIds;
    private static Map<Integer, Surface> fallbackIds;
    private static Set<Integer> reelsGenericIds;
    private static int reelsContainerId;

    private static Class<?> mediaClass;
    private static Class<?> reelItemClass;
    private static Field listenerInfoField;
    private static Field onClickListenerField;

    // Replaced at patch time with Instagram's obfuscated media class name.
    private static String getMediaClassName() {
        return "mediaClassName";
    }

    private static boolean isEnabled() {
        return Pref.enableDownload() && Pref.downloadButton();
    }

    public static void init(Context context) {
        try {
            if (!isEnabled()) return;
            Application application = (Application) context.getApplicationContext();
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    attach(activity);
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

                @Override
                public void onActivityStarted(Activity activity) {}

                @Override
                public void onActivityPaused(Activity activity) {}

                @Override
                public void onActivityStopped(Activity activity) {}

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

                @Override
                public void onActivityDestroyed(Activity activity) {}
            });
        } catch (Exception e) {
            Logger.printException(() -> "DownloadButtonPatch init failure", e);
        }
    }

    private static void attach(Activity activity) {
        try {
            if (activity.getWindow() == null) return;
            View root = activity.getWindow().getDecorView();
            if (!ATTACHED_ROOTS.add(root)) return;

            resolveIds(activity);
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> scheduleScan(root);
            root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            scheduleScan(root);
        } catch (Exception e) {
            Logger.printException(() -> "DownloadButtonPatch attach failure", e);
        }
    }

    private static void scheduleScan(View root) {
        if (!PENDING_ROOTS.add(root)) return;
        HANDLER.postDelayed(() -> {
            PENDING_ROOTS.remove(root);
            try {
                scan(root);
            } catch (Exception e) {
                Logger.printException(() -> "DownloadButtonPatch scan failure", e);
            }
        }, SCAN_DELAY_MS);
    }

    private static int getId(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }

    private static void putIds(Context context, Map<Integer, Surface> map, String[] names, Surface surface) {
        for (String name : names) {
            int id = getId(context, name);
            if (id != 0) map.put(id, surface);
        }
    }

    private static void resolveIds(Context context) {
        if (anchorIds != null) return;

        Map<Integer, Surface> anchors = new HashMap<>();
        putIds(context, anchors, FEED_ANCHORS, Surface.FEED);
        putIds(context, anchors, REELS_ANCHORS, Surface.REELS);
        putIds(context, anchors, STORY_ANCHORS, Surface.STORY);

        Map<Integer, Surface> fallbacks = new HashMap<>();
        putIds(context, fallbacks, new String[]{FEED_FALLBACK}, Surface.FEED);
        putIds(context, fallbacks, new String[]{STORY_FALLBACK}, Surface.STORY);

        Map<Integer, Surface> generic = new HashMap<>();
        putIds(context, generic, REELS_GENERIC_ANCHORS, Surface.REELS);

        reelsContainerId = getId(context, REELS_CONTAINER);
        reelsGenericIds = generic.keySet();
        fallbackIds = fallbacks;
        anchorIds = anchors;

        if (Pref.pikoDebug()) {
            Logger.printInfo(() -> "DownloadButtonPatch anchors=" + anchors.size()
                    + " fallbacks=" + fallbacks.size() + " reelsContainer=" + reelsContainerId);
        }
    }

    private static void scan(View root) {
        if (anchorIds == null) return;

        List<View> anchors = new ArrayList<>();
        List<View> fallbacks = new ArrayList<>();
        List<ViewGroup> reelsContainers = new ArrayList<>();

        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (BUTTON_TAG.equals(view.getTag())) continue;

            int id = view.getId();
            if (id != View.NO_ID) {
                if (anchorIds.containsKey(id)) {
                    anchors.add(view);
                } else if (fallbackIds.containsKey(id)) {
                    fallbacks.add(view);
                } else if (id == reelsContainerId && view instanceof ViewGroup) {
                    reelsContainers.add((ViewGroup) view);
                }
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }

        for (View anchor : anchors) {
            Surface surface = anchorIds.get(anchor.getId());
            Axis axis = surface == Surface.REELS ? Axis.VERTICAL : Axis.HORIZONTAL;
            place(surface, anchor, unitOf(anchor, null), axis);
        }

        for (ViewGroup container : reelsContainers) {
            if (containsButton(container)) continue;
            View save = findReelsSave(container);
            if (save != null) place(Surface.REELS, save, unitOf(save, container), Axis.VERTICAL);
        }

        for (View fallback : fallbacks) {
            Surface surface = fallbackIds.get(fallback.getId());
            ViewParent parent = fallback.getParent();
            if (!(parent instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) parent;
            if (containsButton(group)) continue;
            if (group.getParent() instanceof ViewGroup && containsButton((ViewGroup) group.getParent())) continue;

            View target = surface == Surface.FEED ? rightmostLeftGroupChild(group) : nextVisibleSibling(group, fallback);
            if (target != null) place(surface, target, target, Axis.HORIZONTAL);
        }
    }

    // Save button in the reels column, by id or by its English content description.
    private static View findReelsSave(ViewGroup container) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(container);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view != container) {
                if (reelsGenericIds.contains(view.getId())) return view;
                CharSequence description = view.getContentDescription();
                if (description != null && description.toString().toLowerCase().startsWith("save")) return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        return null;
    }

    // The direct child of `container` holding `anchor`, or the anchor's small wrapper (icon + count).
    private static View unitOf(View anchor, ViewGroup container) {
        View unit = anchor;
        if (container != null) {
            while (unit.getParent() instanceof View && unit.getParent() != container) {
                unit = (View) unit.getParent();
            }
            return unit.getParent() == container ? unit : anchor;
        }
        for (int i = 0; i < 2; i++) {
            ViewParent parent = unit.getParent();
            if (!(parent instanceof ViewGroup)) break;
            ViewGroup group = (ViewGroup) parent;
            if (group.getChildCount() > 2 || group.getId() == reelsContainerId) break;
            unit = group;
        }
        return unit;
    }

    // Feed row fallback: the last button of the like/comment/share group, ignoring save on the right edge.
    private static View rightmostLeftGroupChild(ViewGroup group) {
        int limit = (int) (group.getWidth() * 0.75f);
        View best = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || BUTTON_TAG.equals(child.getTag())) continue;
            if (child.getRight() > limit) continue;
            if (best == null || child.getRight() > best.getRight()) best = child;
        }
        return best;
    }

    private static View nextVisibleSibling(ViewGroup group, View view) {
        for (int i = group.indexOfChild(view) + 1; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && !BUTTON_TAG.equals(child.getTag())) return child;
        }
        return view;
    }

    private static boolean containsButton(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (BUTTON_TAG.equals(group.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static void place(Surface surface, View anchor, View unit, Axis axis) {
        try {
            ViewParent viewParent = unit.getParent();
            if (!(viewParent instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) viewParent;
            if (containsButton(parent)) return;

            int width = anchor.getWidth();
            int height = anchor.getHeight();
            if (width <= 0 || height <= 0) return;

            ImageView button = createButton(parent.getContext(), surface, anchor);
            int index = parent.indexOfChild(unit) + 1;

            boolean linear = parent instanceof LinearLayout
                    && ((LinearLayout) parent).getOrientation()
                    == (axis == Axis.HORIZONTAL ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

            if (linear) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
                params.gravity = android.view.Gravity.CENTER;
                parent.addView(button, index, params);
            } else {
                // Unknown layout: take the unit's own params so it sits where the unit does, then shift it over.
                ViewGroup.LayoutParams params = copyLayoutParams(unit.getLayoutParams());
                if (params == null) return;
                params.width = width;
                params.height = height;
                if (axis == Axis.HORIZONTAL) {
                    button.setTranslationX(unit.getWidth());
                } else if (surface == Surface.STORY) {
                    button.setTranslationY(-height);
                } else {
                    button.setTranslationY(unit.getHeight());
                }
                parent.addView(button, index, params);
            }
        } catch (Exception e) {
            Logger.printException(() -> "DownloadButtonPatch place failure", e);
        }
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
        if (source == null) return null;
        try {
            Constructor<? extends ViewGroup.LayoutParams> constructor =
                    source.getClass().getConstructor(source.getClass());
            return constructor.newInstance(source);
        } catch (Exception ignored) {
        }
        if (source instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) source);
        }
        return new ViewGroup.LayoutParams(source);
    }

    private static ImageView createButton(Context context, Surface surface, View anchor) {
        ImageView button = new ImageView(context);
        button.setTag(BUTTON_TAG);
        button.setContentDescription(str("piko_download_button"));
        UI.setThemedIcon(button, UI.DRAWABLE_DOWNLOAD_ICON);
        if (surface != Surface.FEED) {
            // Reels and stories draw white icons over the media.
            button.setColorFilter(Color.WHITE);
        }
        if (anchor instanceof ImageView) {
            button.setScaleType(((ImageView) anchor).getScaleType());
        } else {
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
        button.setPadding(anchor.getPaddingLeft(), anchor.getPaddingTop(), anchor.getPaddingRight(), anchor.getPaddingBottom());
        button.setClickable(true);
        button.setOnClickListener(v -> onButtonClick(v, surface, anchor));
        return button;
    }

    private static void onButtonClick(View button, Surface surface, View anchor) {
        try {
            Object[] found = findMedia(button, anchor);
            Object media = found[0];
            if (media == null) {
                Utils.showToastShort(str("piko_download_button_no_media"));
                return;
            }
            UserSession userSession = (UserSession) found[1];
            MediaData mediaData = new MediaData(media, userSession);

            // Albums download every item; stories download the item on screen.
            int position = surface != Surface.STORY && mediaData.getCarouselSize() > 1 ? -1 : 0;
            DownloadUtils.downloadMedia(button.getContext(), mediaData, position, MediaType.ANY);
        } catch (Exception e) {
            PikoUtils.logger(e);
            Logger.printException(() -> "DownloadButtonPatch click failure", e);
            Utils.showToastShort(e.getMessage());
        }
    }

    private static Class<?> mediaClass() throws Exception {
        if (mediaClass == null) mediaClass = Class.forName(getMediaClassName());
        return mediaClass;
    }

    private static Class<?> reelItemClass() {
        if (reelItemClass == null) {
            try {
                reelItemClass = Class.forName(REEL_ITEM_CLASS);
            } catch (Exception ignored) {
                reelItemClass = Void.class;
            }
        }
        return reelItemClass;
    }

    private static View.OnClickListener getOnClickListener(View view) {
        try {
            if (listenerInfoField == null) {
                listenerInfoField = View.class.getDeclaredField("mListenerInfo");
                listenerInfoField.setAccessible(true);
            }
            Object listenerInfo = listenerInfoField.get(view);
            if (listenerInfo == null) return null;
            if (onClickListenerField == null) {
                onClickListenerField = listenerInfo.getClass().getDeclaredField("mOnClickListener");
                onClickListenerField.setAccessible(true);
            }
            return (View.OnClickListener) onClickListenerField.get(listenerInfo);
        } catch (Exception e) {
            return null;
        }
    }

    // Returns {media, userSession}; either may be null.
    private static Object[] findMedia(View button, View anchor) throws Exception {
        Class<?> media = mediaClass();
        Class<?> reelItem = reelItemClass();

        // Seeds, nearest first: the anchor's listener, then each ancestor's listener, tag, layout params
        // and the view itself, up to the list that holds the row. The list itself is not a seed, as its
        // adapter can lead to the media of other rows.
        List<Object> seeds = new ArrayList<>();
        addSeed(seeds, getOnClickListener(anchor));
        View view = anchor;
        for (int i = 0; i < MAX_ANCESTORS && view != null && !isListView(view); i++) {
            addSeed(seeds, getOnClickListener(view));
            addSeed(seeds, view.getTag());
            addSeed(seeds, view.getLayoutParams());
            addSeed(seeds, view);
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }

        Map<Object, Boolean> visited = new IdentityHashMap<>();
        UserSession[] userSession = new UserSession[1];
        Object result = null;
        for (Object seed : seeds) {
            result = searchFrom(seed, media, reelItem, visited, userSession);
            if (result != null) break;
        }

        if (result == null && Pref.pikoDebug()) {
            Logger.printInfo(() -> "DownloadButtonPatch no media, seeds=" + seeds.size() + " visited=" + visited.size());
        }
        return new Object[]{result, userSession[0]};
    }

    private static boolean isListView(View view) {
        if (view instanceof android.widget.AdapterView) return true;
        for (Class<?> c = view.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            if (c.getName().endsWith("RecyclerView")) return true;
        }
        return false;
    }

    // Breadth first search from one seed for a media object or a ReelItem holding one.
    private static Object searchFrom(Object seed, Class<?> media, Class<?> reelItem,
                                     Map<Object, Boolean> visited, UserSession[] userSession) {
        ArrayDeque<Object> current = new ArrayDeque<>();
        current.add(seed);
        int budget = MAX_SEARCH_OBJECTS;
        for (int depth = 0; depth <= MAX_SEARCH_DEPTH && !current.isEmpty(); depth++) {
            ArrayDeque<Object> next = new ArrayDeque<>();
            while (!current.isEmpty()) {
                Object obj = current.poll();
                if (visited.containsKey(obj) || budget-- <= 0) continue;
                visited.put(obj, Boolean.TRUE);

                if (media.isInstance(obj)) return obj;
                if (reelItem.isInstance(obj)) {
                    Object reelMedia = mediaOfReelItem(obj, media);
                    if (reelMedia != null) return reelMedia;
                }
                if (obj instanceof UserSession) {
                    if (userSession[0] == null) userSession[0] = (UserSession) obj;
                    continue;
                }
                collectFields(obj, next);
            }
            current = next;
        }
        return null;
    }

    private static void addSeed(List<Object> seeds, Object obj) {
        if (obj != null) seeds.add(obj);
    }

    private static Object mediaOfReelItem(Object reelItem, Class<?> media) {
        Object found = null;
        for (Class<?> c = reelItem.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !media.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(reelItem);
                    // The patch side uses the last media field of ReelItem as well.
                    if (value != null) found = value;
                } catch (Exception ignored) {
                }
            }
        }
        return found;
    }

    private static boolean isFrameworkClass(Class<?> c) {
        String name = c.getName();
        return name.startsWith("android.") || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("kotlin.") || name.startsWith("dalvik.") || name.startsWith("libcore.")
                || name.startsWith("sun.") || name.startsWith("com.android.");
    }

    // Reads instance fields of app classes. Framework internals, collections and arrays are not walked,
    // so the search stays near the views and does not pick the media of another row.
    private static void collectFields(Object obj, ArrayDeque<Object> out) {
        if (obj instanceof Collection || obj instanceof Map || obj.getClass().isArray()) return;
        if (obj instanceof CharSequence || obj instanceof Number || obj instanceof Boolean) return;

        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (isFrameworkClass(c)) continue;
            for (Field field : c.getDeclaredFields()) {
                Class<?> type = field.getType();
                if (Modifier.isStatic(field.getModifiers()) || type.isPrimitive() || type == String.class) continue;
                if (Context.class.isAssignableFrom(type)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value != null && !(value instanceof Context) && !(value instanceof View && isListView((View) value))) {
                        out.add(value);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
