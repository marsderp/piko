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
    private static final String[] STORY_ANCHORS = {
            "toolbar_reshare_button", "toolbar_share_button", "reel_viewer_share_button", "toolbar_direct_share_button"
    };

    // Used when no anchor above exists in this Instagram version.
    private static final String FEED_FALLBACK = "row_feed_button_comment";
    private static final String STORY_FALLBACK = "toolbar_like_button";
    // The reels button column is one Litho component: like_button, comment_button, direct_share_button,
    // save_button and clips_ufi_more_button_component are its views, while the counts are drawn by the host
    // and are not views. Nothing can be inserted into it, so the button goes above like, in line with the column.
    private static final String REELS_COLUMN = "clips_ufi_component";
    private static final String REELS_LIKE = "like_button";
    // The story bottom bar row (like, reshare, menu), which takes extra buttons like piko's own seen button.
    private static final String STORY_ROW = "toolbar_buttons_container";
    private static final String STORY_LIKE_CONTAINER = "toolbar_like_container";

    // Views of the feed row, used to tell its save button apart from the reels one.
    private static final String[] FEED_ROW_IDS = {
            "row_feed_view_group_buttons", "row_feed_button_save", "row_feed_button_share", "row_feed_button_like"
    };

    private static final String REEL_ITEM_CLASS = "com.instagram.model.reels.ReelItem";

    private static final int SCAN_DELAY_MS = 150;
    private static final int MAX_ANCESTORS = 14;
    private static final int MAX_SEARCH_DEPTH = 7;
    private static final int MAX_SEARCH_OBJECTS = 3000;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<View> ATTACHED_ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> PENDING_ROOTS = Collections.newSetFromMap(new WeakHashMap<>());

    private static Map<Integer, Surface> anchorIds;
    private static Map<Integer, Surface> fallbackIds;
    private static Set<Integer> feedRowIds = Collections.emptySet();
    private static int reelsColumnId;
    private static int reelsLikeId;
    private static int storyRowId;
    private static int storyLikeContainerId;

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

    // Called from Application.onCreate, which runs before piko's settings status is loaded,
    // so whether the button is enabled is only checked once an activity resumes.
    public static void init(Context context) {
        try {
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
            if (activity.getWindow() == null || !isEnabled()) return;
            lastActivity = new java.lang.ref.WeakReference<>(activity);
            View root = activity.getWindow().getDecorView();
            if (!ATTACHED_ROOTS.add(root)) return;

            resolveIds(activity);
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> scheduleScan(root);
            root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            root.getViewTreeObserver().addOnPreDrawListener(() -> {
                try {
                    if (!OVERLAY_LIST.isEmpty()) updateOverlays();
                } catch (Exception e) {
                    Logger.printException(() -> "DownloadButtonPatch overlay update failure", e);
                }
                return true;
            });
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
                showDebugReport(root.getContext(), "Download button scan failure: " + e, e);
            }
        }, SCAN_DELAY_MS);
    }

    private static int getId(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }

    // Names of the ids that exist in this Instagram version, shown in debug mode.
    private static final List<String> RESOLVED_NAMES = new ArrayList<>();

    private static void putIds(Context context, Map<Integer, Surface> map, String[] names, Surface surface) {
        for (String name : names) {
            int id = getId(context, name);
            if (id != 0) {
                map.put(id, surface);
                RESOLVED_NAMES.add(name);
            }
        }
    }

    private static void resolveIds(Context context) {
        if (anchorIds != null) return;

        Map<Integer, Surface> anchors = new HashMap<>();
        putIds(context, anchors, FEED_ANCHORS, Surface.FEED);
        putIds(context, anchors, STORY_ANCHORS, Surface.STORY);

        Map<Integer, Surface> fallbacks = new HashMap<>();
        putIds(context, fallbacks, new String[]{FEED_FALLBACK}, Surface.FEED);
        putIds(context, fallbacks, new String[]{STORY_FALLBACK}, Surface.STORY);


        Map<Integer, Surface> feedRow = new HashMap<>();
        putIds(context, feedRow, FEED_ROW_IDS, Surface.FEED);
        feedRowIds = feedRow.keySet();

        reelsColumnId = getId(context, REELS_COLUMN);
        if (reelsColumnId != 0) RESOLVED_NAMES.add(REELS_COLUMN);
        reelsLikeId = getId(context, REELS_LIKE);
        storyRowId = getId(context, STORY_ROW);
        if (storyRowId != 0) RESOLVED_NAMES.add(STORY_ROW);
        storyLikeContainerId = getId(context, STORY_LIKE_CONTAINER);
        fallbackIds = fallbacks;
        anchorIds = anchors;

        if (Pref.pikoDebug()) {
            String message = "Download button ids: " + (RESOLVED_NAMES.isEmpty() ? "none" : String.join(", ", RESOLVED_NAMES));
            Logger.printInfo(() -> message);
            Utils.showToastLong(message);
        }
    }

    private static void scan(View root) {
        if (anchorIds == null) return;

        List<View> anchors = new ArrayList<>();
        List<View> fallbacks = new ArrayList<>();
        List<View> reelsLikes = new ArrayList<>();
        List<ViewGroup> storyRows = new ArrayList<>();
        // Without the column id, the reels like button is found by its label on the right edge.
        View reelsLike = null;
        int rootWidth = root.getWidth();

        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (BUTTON_TAG.equals(view.getTag())) continue;

            int id = view.getId();
            if (id != View.NO_ID) {
                if (id == storyRowId && view instanceof ViewGroup) {
                    storyRows.add((ViewGroup) view);
                } else if (storyRowId != 0 && hasAncestor(view, storyRowId, 4)) {
                    // Stories with the bar row get their button in the row below.
                } else if (anchorIds.containsKey(id)) {
                    anchors.add(view);
                } else if (fallbackIds.containsKey(id)) {
                    fallbacks.add(view);
                } else if (id == reelsLikeId && reelsColumnId != 0 && hasAncestor(view, reelsColumnId, 4)) {
                    reelsLikes.add(view);
                }
            }

            if (reelsLike == null && reelsLikes.isEmpty()) {
                String label = rightColumnLabel(view, rootWidth, root.getHeight());
                if (label != null && label.equals("like")) reelsLike = view;
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }

        for (View anchor : anchors) {
            place(anchorIds.get(anchor.getId()), anchor, unitOf(anchor, null), Axis.HORIZONTAL);
        }

        for (ViewGroup row : storyRows) placeStoryRow(row);

        // Counts change as feed rows are reused, so measure them again on every scan.
        for (Overlay overlay : OVERLAY_LIST) {
            View anchor = overlay.anchor.get();
            if (overlay.surface == Surface.FEED && anchor != null && isFullyVisible(anchor)) {
                overlay.inkExtent = measureTrailingInk(anchor);
            }
        }

        if (reelsLike != null) reelsLikes.add(reelsLike);
        for (View like : reelsLikes) {
            if (isFullyVisible(like)) {
                placeReels(like);
                break;
            }
        }

        for (View fallback : fallbacks) {
            Surface surface = fallbackIds.get(fallback.getId());
            ViewParent parent = fallback.getParent();
            if (!(parent instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) parent;
            if (containsButton(group)) continue;
            if (group.getParent() instanceof ViewGroup && containsButton((ViewGroup) group.getParent())) continue;
            if (hasOverlayIn(group)) continue;

            View target = surface == Surface.FEED ? rightmostLeftGroupChild(group) : nextVisibleSibling(group, fallback);
            if (target != null) place(surface, target, target, Axis.HORIZONTAL);
        }
    }

    // Only one reels button exists at a time. It moves to the reel on screen once the previous one is gone,
    // so scrolling through reels does not leave a button behind on every page.
    private static void placeReels(View like) {
        for (int i = OVERLAY_LIST.size() - 1; i >= 0; i--) {
            Overlay overlay = OVERLAY_LIST.get(i);
            if (overlay.surface != Surface.REELS) continue;
            View other = overlay.anchor.get();
            if (other == like) return;
            if (other != null && isReelsLike(other) && isFullyVisible(other)) return;
            removeOverlay(overlay, i);
        }
        // Always an overlay: the column does not take extra views.
        try {
            if (OVERLAYS.containsKey(like) || FAILED_ANCHORS.contains(like) || like.getHeight() <= 0) return;
            int size = Math.min(like.getWidth(), like.getHeight());
            ImageView button = createButton(like.getContext(), Surface.REELS, like);
            // The download icon is heavier than Instagram's outline icons, so draw it a bit smaller.
            int inset = size / 7;
            button.setPadding(inset, inset, inset, inset);
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addAsOverlay(button, Surface.REELS, like, Axis.VERTICAL, size, size);
        } catch (Exception e) {
            FAILED_ANCHORS.add(like);
            Logger.printInfo(() -> "DownloadButtonPatch reels place failure: " + e);
            showDebugReport(like.getContext(), "Download button reels place failure: " + e, e);
        }
    }

    // One button per story, in the bar row: after share, or after like when the story cannot be reshared.
    private static void placeStoryRow(ViewGroup row) {
        if (FAILED_ANCHORS.contains(row)) return;
        View anchor = null;
        for (String name : STORY_ANCHORS) {
            int id = getId(row.getContext(), name);
            View share = id == 0 ? null : row.findViewById(id);
            if (share != null && share.getVisibility() == View.VISIBLE && share.getWidth() > 0) {
                anchor = childToward(row, share);
                break;
            }
        }
        if (anchor == null && storyLikeContainerId != 0) {
            View like = row.findViewById(storyLikeContainerId);
            if (like != null && like.getVisibility() == View.VISIBLE) anchor = childToward(row, like);
        }
        if (anchor == null || anchor.getParent() != row || anchor.getHeight() <= 0) return;

        // The row is reused for the next story, which may or may not have share, so keep the button after the anchor.
        View existing = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (BUTTON_TAG.equals(row.getChildAt(i).getTag())) existing = row.getChildAt(i);
        }
        if (existing != null) {
            if (row.indexOfChild(existing) == row.indexOfChild(anchor) + 1) return;
            row.removeView(existing);
        }

        try {
            int size = anchor.getHeight();
            ViewGroup.LayoutParams params = copyLayoutParams(anchor.getLayoutParams());
            if (params == null) params = new ViewGroup.LayoutParams(size, size);
            params.width = size;
            params.height = size;
            if (params instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) params).weight = 0;
            ImageView button = createButton(row.getContext(), Surface.STORY, anchor);
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            row.addView(button, row.indexOfChild(anchor) + 1, params);
        } catch (Exception e) {
            FAILED_ANCHORS.add(row);
            reportFailure("story row", row, e);
        }
    }

    private static boolean isReelsLike(View view) {
        if (reelsLikeId != 0 && view.getId() == reelsLikeId) return true;
        CharSequence description = view.getContentDescription();
        return description != null && description.toString().equalsIgnoreCase("like");
    }

    private static boolean hasAncestor(View view, int id, int levels) {
        ViewParent parent = view.getParent();
        for (int i = 0; i < levels && parent instanceof View; i++) {
            if (((View) parent).getId() == id) return true;
            parent = parent.getParent();
        }
        return false;
    }

    // Lower case content description of a small button on the right edge of the screen outside feed rows, else null.
    private static String rightColumnLabel(View view, int rootWidth, int rootHeight) {
        // Count labels like "35.4K saves" are not buttons.
        if (view instanceof android.widget.TextView) return null;
        CharSequence description = view.getContentDescription();
        if (description == null || description.length() == 0 || rootWidth <= 0) return null;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0 || width > rootWidth / 4 || height > rootWidth / 4) return null;
        view.getLocationInWindow(LABEL_LOCATION);
        int centerX = LABEL_LOCATION[0] + width / 2;
        int centerY = LABEL_LOCATION[1] + height / 2;
        // Only the reel on screen, not the pages next to it.
        if (centerX < rootWidth * 3 / 4 || centerX > rootWidth || centerY < 0 || centerY > rootHeight) return null;
        if (inFeedRow(view)) return null;
        return description.toString().toLowerCase();
    }

    private static final int[] LABEL_LOCATION = new int[2];

    // Feed rows have their own save button on the right edge, which must not be taken for the reels one.
    private static boolean inFeedRow(View view) {
        View current = view;
        for (int i = 0; i < 6 && current != null; i++) {
            if (feedRowIds.contains(current.getId())) return true;
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int c = 0; c < group.getChildCount(); c++) {
                    if (feedRowIds.contains(group.getChildAt(c).getId())) return true;
                }
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static View ancestor(View view, int levels) {
        View current = view;
        for (int i = 0; i < levels && current.getParent() instanceof View; i++) {
            current = (View) current.getParent();
        }
        return current;
    }

    // Debug mode: id name, class and label of each view, so the real reels ids can be copied.
    private static String describeTree(View root) {
        StringBuilder out = new StringBuilder();
        describeTree(root, 0, out);
        return out.toString();
    }

    private static void describeTree(View view, int depth, StringBuilder out) {
        if (depth > 8 || out.length() > 6000) return;
        for (int i = 0; i < depth; i++) out.append("  ");
        String idName = "-";
        if (view.getId() != View.NO_ID) {
            try {
                idName = view.getResources().getResourceEntryName(view.getId());
            } catch (Exception ignored) {
                idName = String.valueOf(view.getId());
            }
        }
        out.append(idName).append(' ').append(view.getClass().getSimpleName());
        if (view.getContentDescription() != null) out.append(" \"").append(view.getContentDescription()).append('"');
        if (view instanceof android.widget.TextView) {
            out.append(" text=\"").append(((android.widget.TextView) view).getText()).append('"');
        }
        if (view.getVisibility() != View.VISIBLE) out.append(" hidden");
        out.append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) describeTree(group.getChildAt(i), depth + 1, out);
        }
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
            if (group.getChildCount() > 2 || group.getId() == reelsColumnId) break;
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

    private static boolean hasOverlayIn(ViewGroup group) {
        for (View anchor : OVERLAYS.keySet()) {
            for (ViewParent p = anchor.getParent(); p != null; p = p.getParent()) {
                if (p == group) return true;
            }
        }
        return false;
    }

    private static boolean containsButton(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (BUTTON_TAG.equals(group.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static void place(Surface surface, View anchor, View unit, Axis axis) {
        try {
            placeUnchecked(surface, anchor, unit, axis);
        } catch (Exception e) {
            // Never let one anchor stop the scan of the others.
            FAILED_ANCHORS.add(anchor);
            Logger.printInfo(() -> "DownloadButtonPatch place failure: " + e);
            showDebugReport(anchor.getContext(), "Download button place failure: " + e, e);
        }
    }

    private static void placeUnchecked(Surface surface, View anchor, View unit, Axis axis) {
        if (OVERLAYS.containsKey(anchor) || FAILED_ANCHORS.contains(anchor)) return;

        ViewParent viewParent = unit.getParent();
        if (!(viewParent instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) viewParent;
        if (containsButton(parent)) return;

        int width = anchor.getWidth();
        int height = anchor.getHeight();
        if (width <= 0 || height <= 0) return;
        if (surface == Surface.REELS) {
            // The save view can include its count, so keep the button the size of the icon.
            width = height = Math.min(width, height);
        }

        ImageView button = createButton(parent.getContext(), surface, anchor);
        try {
            // Litho hosts lay out and draw their own content, so an added child would overlap it.
            // Feed rows can keep the share count in a separate view after share, so a button added to
            // the row lands between them. The overlay measures the drawn count instead.
            boolean litho = parent.getClass().getName().contains("ComponentHost");
            if (!litho && surface != Surface.FEED && addToParent(parent, button, surface, unit, axis, width, height)) return;
        } catch (Exception e) {
            // Some of Instagram's containers do not accept extra children. Some (like AdapterView)
            // also throw on removeView, so only remove the button if it was actually added.
            if (button.getParent() == parent) {
                try {
                    parent.removeView(button);
                } catch (Exception ignored) {
                }
            }
            reportFailure("in row", parent, e);
        }
        if (button.getParent() != null) return;

        try {
            addAsOverlay(button, surface, anchor, axis, width, height);
        } catch (Exception e) {
            FAILED_ANCHORS.add(anchor);
            reportFailure("overlay", parent, e);
        }
    }

    // Returns false when the parent is not a layout the button can safely join.
    private static boolean addToParent(ViewGroup parent, ImageView button, Surface surface, View unit,
                                       Axis axis, int width, int height) {
        // Reels: between share and save, so before the save button.
        int index = parent.indexOfChild(unit) + (surface == Surface.REELS ? 0 : 1);

        boolean linear = parent instanceof LinearLayout
                && ((LinearLayout) parent).getOrientation()
                == (axis == Axis.HORIZONTAL ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        if (linear) {
            if (axis == Axis.HORIZONTAL) {
                // After the count label of the button, like the share count of a feed post.
                while (index < parent.getChildCount() && parent.getChildAt(index) instanceof android.widget.TextView) {
                    index++;
                }
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
            if (surface == Surface.FEED) params.leftMargin = feedGap(parent.getContext());
            params.gravity = android.view.Gravity.CENTER;
            parent.addView(button, index, params);
            return true;
        }
        if (!(parent instanceof android.widget.FrameLayout || parent instanceof android.widget.RelativeLayout
                || parent.getClass().getName().endsWith("ConstraintLayout"))) {
            return false;
        }

        // Take the unit's own params so the button sits where the unit does, then shift it over.
        ViewGroup.LayoutParams params = copyLayoutParams(unit.getLayoutParams());
        params.width = width;
        params.height = height;
        button.setTranslationX(offsetX(surface, axis, unit.getWidth()) + labelExtent(unit, surface, axis, Axis.HORIZONTAL)
                + (surface == Surface.FEED ? feedGap(parent.getContext()) : 0));
        button.setTranslationY(offsetY(surface, axis, unit.getHeight(), height) + labelExtent(unit, surface, axis, Axis.VERTICAL));
        parent.addView(button, index, params);
        return true;
    }

    // How far count labels reach past the unit along the placement axis.
    private static int labelExtent(View unit, Surface surface, Axis axis, Axis direction) {
        if (surface == Surface.STORY || axis != direction) return 0;
        unit.getLocationInWindow(LOCATION);
        int end = direction == Axis.HORIZONTAL ? LOCATION[0] + unit.getWidth() : LOCATION[1] + unit.getHeight();
        return Math.max(0, trailingEdge(unit, direction) - end);
    }

    // Space between share (or its count) and the download button on feed posts.
    private static int feedGap(Context context) {
        return (int) (10 * context.getResources().getDisplayMetrics().density);
    }

    private static int offsetX(Surface surface, Axis axis, int unitWidth) {
        return axis == Axis.HORIZONTAL && surface != Surface.STORY ? unitWidth : 0;
    }

    private static int offsetY(Surface surface, Axis axis, int unitHeight, int height) {
        if (surface == Surface.STORY) return -height;
        return axis == Axis.VERTICAL ? unitHeight : 0;
    }

    // Overlay mode: the button lives in the activity's content view and follows the anchor on every frame.
    private static final class Overlay {
        final java.lang.ref.WeakReference<View> anchor;
        final ImageView button;
        final Surface surface;
        final Axis axis;
        // Feed: how far the count drawn right of the anchor reaches, measured on each scan.
        int inkExtent;

        Overlay(View anchor, ImageView button, Surface surface, Axis axis) {
            this.anchor = new java.lang.ref.WeakReference<>(anchor);
            this.button = button;
            this.surface = surface;
            this.axis = axis;
        }
    }

    private static final Map<View, Overlay> OVERLAYS = new WeakHashMap<>();
    private static final List<Overlay> OVERLAY_LIST = new ArrayList<>();
    private static final Set<View> FAILED_ANCHORS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<String> REPORTED_FAILURES = new java.util.HashSet<>();
    private static final android.graphics.Rect VISIBLE_RECT = new android.graphics.Rect();
    private static final int[] LOCATION = new int[2];
    private static final int[] HOST_LOCATION = new int[2];

    private static void addAsOverlay(ImageView button, Surface surface, View anchor, Axis axis, int width, int height) {
        View content = anchor.getRootView().findViewById(android.R.id.content);
        if (!(content instanceof android.widget.FrameLayout)) {
            throw new IllegalStateException("no content view: " + content);
        }
        // Stories already look right on top of the content view, so only feed and reels move.
        android.widget.FrameLayout host = surface == Surface.STORY ? (android.widget.FrameLayout) content
                : overlayHost(anchor, (android.widget.FrameLayout) content);
        try {
            // Right above the branch holding the list, so views added later (sheets) stay on top.
            host.addView(button, host == content ? -1 : host.indexOfChild(childToward(host, anchor)) + 1,
                    new android.widget.FrameLayout.LayoutParams(width, height));
        } catch (Exception e) {
            if (host == content) throw e;
            if (button.getParent() == host) host.removeView(button);
            host = (android.widget.FrameLayout) content;
            host.addView(button, new android.widget.FrameLayout.LayoutParams(width, height));
        }

        Overlay overlay = new Overlay(anchor, button, surface, axis);
        if (surface == Surface.FEED) overlay.inkExtent = measureTrailingInk(anchor);
        OVERLAYS.put(anchor, overlay);
        OVERLAY_LIST.add(overlay);
        positionOverlay(overlay);

        if (surface == Surface.FEED && Pref.pikoDebug() && !feedTreeShown) {
            feedTreeShown = true;
            showDebugReport(anchor.getContext(), "Download button: feed row (debug info, nothing failed). Count width: "
                    + overlay.inkExtent + "px\n\n" + describeTree(ancestor(anchor, 2)), null);
        }
    }

    private static boolean feedTreeShown;

    private static View childToward(ViewGroup group, View view) {
        while (view.getParent() instanceof View && view.getParent() != group) view = (View) view.getParent();
        return view;
    }

    // The frame around the scrolling list that holds the anchor (the feed list or the reels pager).
    // Share and more sheets are added above that frame, so they cover the button like the rest of the
    // post. Falls back to the content view when there is no such list.
    private static android.widget.FrameLayout overlayHost(View anchor, android.widget.FrameLayout content) {
        boolean pastList = false;
        for (ViewParent p = anchor.getParent(); p instanceof View && p != content; p = p.getParent()) {
            String name = p.getClass().getName();
            if (name.contains("RecyclerView") || name.contains("ViewPager") || name.contains("ListView")) {
                pastList = true;
            } else if (pastList && p instanceof android.widget.FrameLayout) {
                return (android.widget.FrameLayout) p;
            }
        }
        return content;
    }

    // Litho draws the counts of the feed row inside its host view, so they are not TextViews and
    // cannot be measured as views. This draws the strip right of the anchor and returns how far
    // non-background pixels reach, so the button can go after the count.
    private static int measureTrailingInk(View anchor) {
        try {
            int width = anchor.getWidth();
            int height = anchor.getHeight();
            if (width <= 0 || height <= 0) return 0;

            // The nearest ancestor wide enough to hold the count, with the anchor's offset inside it.
            View host = anchor;
            int offsetX = 0;
            int offsetY = 0;
            while (host.getParent() instanceof View && host.getWidth() < width * 4) {
                offsetX += host.getLeft() + (int) host.getTranslationX();
                offsetY += host.getTop() + (int) host.getTranslationY();
                host = (View) host.getParent();
            }
            if (host == anchor) return 0;

            int startX = offsetX + width;
            int span = Math.min(width * 4, host.getWidth() - startX);
            if (span <= 0) return 0;

            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(span, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.translate(-startX, -offsetY);
            host.draw(canvas);
            int[] pixels = new int[span * height];
            bitmap.getPixels(pixels, 0, span, 0, 0, span, height);
            bitmap.recycle();

            // The top row of the strip is above the text, so it holds the background.
            int background = pixels[0];
            int last = -1;
            int emptyRun = 0;
            for (int x = 0; x < span; x++) {
                boolean ink = false;
                for (int y = height / 4; y < height * 3 / 4 && !ink; y++) {
                    ink = colorDistance(pixels[y * span + x], background) > 96;
                }
                if (ink) {
                    last = x;
                    emptyRun = 0;
                } else {
                    emptyRun++;
                    // Stop at the gap after the count, or when nothing starts close to the anchor.
                    if (last >= 0 ? emptyRun > width / 2 : x > width / 2) break;
                }
            }
            return last + 1;
        } catch (Throwable e) {
            Logger.printInfo(() -> "DownloadButtonPatch count measure failure: " + e);
            return 0;
        }
    }

    private static int colorDistance(int a, int b) {
        return Math.abs(Color.alpha(a) - Color.alpha(b)) + Math.abs(Color.red(a) - Color.red(b))
                + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
    }

    private static void removeOverlay(Overlay overlay, int index) {
        ViewParent host = overlay.button.getParent();
        if (host instanceof ViewGroup) ((ViewGroup) host).removeView(overlay.button);
        View anchor = overlay.anchor.get();
        if (anchor != null) OVERLAYS.remove(anchor);
        OVERLAY_LIST.remove(index);
    }

    private static void updateOverlays() {
        for (int i = OVERLAY_LIST.size() - 1; i >= 0; i--) {
            Overlay overlay = OVERLAY_LIST.get(i);
            View anchor = overlay.anchor.get();
            if (anchor == null || !overlay.button.isAttachedToWindow()) {
                removeOverlay(overlay, i);
                continue;
            }
            // Litho reuses the views of the reels column, so the like view of one reel can become the
            // comment or save view of the next. Drop the button then and let the next scan find like again.
            if (overlay.surface == Surface.REELS && !isReelsLike(anchor)) {
                removeOverlay(overlay, i);
                scheduleScan(anchor.getRootView());
                continue;
            }
            positionOverlay(overlay);
        }
    }

    private static void positionOverlay(Overlay overlay) {
        View anchor = overlay.anchor.get();
        View host = (View) overlay.button.getParent();
        if (anchor == null || host == null) return;

        // Only while the anchor is fully on screen, so the button never floats over the tab bar or headers.
        boolean shown = anchor.getRootView() == host.getRootView() && isFullyVisible(anchor);
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (overlay.button.getVisibility() != visibility) overlay.button.setVisibility(visibility);
        if (!shown) return;

        anchor.getLocationInWindow(LOCATION);
        host.getLocationInWindow(HOST_LOCATION);
        int width = anchor.getWidth();
        int height = anchor.getHeight();
        int left = LOCATION[0];
        int top = LOCATION[1];
        float x;
        float y;
        if (overlay.surface == Surface.STORY) {
            x = left;
            y = top - height;
        } else if (overlay.axis == Axis.HORIZONTAL) {
            // After the count label too, like the share count of a feed post.
            x = trailingEdge(anchor, Axis.HORIZONTAL);
            if (overlay.surface == Surface.FEED) {
                x = Math.max(x, left + width + overlay.inkExtent) + feedGap(anchor.getContext());
            }
            y = top;
        } else {
            // Reels: above like, in line with the column, with the column's own spacing.
            int size = overlay.button.getLayoutParams().width;
            x = left + (width - size) / 2f;
            y = top - size - size / 2f;
        }
        // The host lays the button out inside its padding.
        x -= HOST_LOCATION[0] + host.getPaddingLeft();
        y -= HOST_LOCATION[1] + host.getPaddingTop();
        if (overlay.button.getTranslationX() != x) overlay.button.setTranslationX(x);
        if (overlay.button.getTranslationY() != y) overlay.button.setTranslationY(y);
    }

    private static final int[] EDGE_LOCATION = new int[2];
    private static final int[] TEXT_LOCATION = new int[2];

    // Window x of the anchor's right edge (or y of its bottom edge), moved past count labels that directly follow it.
    private static int trailingEdge(View anchor, Axis axis) {
        anchor.getLocationInWindow(EDGE_LOCATION);
        int left = EDGE_LOCATION[0];
        int top = EDGE_LOCATION[1];
        int width = anchor.getWidth();
        int height = anchor.getHeight();
        boolean horizontal = axis == Axis.HORIZONTAL;
        int edge = horizontal ? left + width : top + height;
        int size = horizontal ? height : width;

        List<int[]> labels = new ArrayList<>();
        collectLabels(ancestor(anchor, 2), anchor, 0, labels);
        boolean moved = true;
        while (moved) {
            moved = false;
            for (int[] r : labels) {
                // r = {left, top, right, bottom}
                int start = horizontal ? r[0] : r[1];
                int end = horizontal ? r[2] : r[3];
                int center = horizontal ? (r[1] + r[3]) / 2 : (r[0] + r[2]) / 2;
                boolean inLine = horizontal ? center >= top && center <= top + height : center >= left && center <= left + width;
                if (inLine && end > edge && start >= edge - size / 2 && start <= edge + size / 2) {
                    edge = end;
                    moved = true;
                }
            }
        }
        return edge;
    }

    private static void collectLabels(View view, View anchor, int depth, List<int[]> out) {
        if (view.getVisibility() != View.VISIBLE || BUTTON_TAG.equals(view.getTag())) return;
        if (view instanceof android.widget.TextView && view != anchor) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.length() > 0 && view.getWidth() > 0) {
                view.getLocationInWindow(TEXT_LOCATION);
                out.add(new int[]{TEXT_LOCATION[0], TEXT_LOCATION[1],
                        TEXT_LOCATION[0] + view.getWidth(), TEXT_LOCATION[1] + view.getHeight()});
            }
            return;
        }
        if (depth >= 4 || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectLabels(group.getChildAt(i), anchor, depth + 1, out);
    }

    private static boolean isFullyVisible(View view) {
        return view.isAttachedToWindow() && view.isShown() && view.getWidth() > 0
                && view.getGlobalVisibleRect(VISIBLE_RECT)
                && VISIBLE_RECT.height() == view.getHeight() && VISIBLE_RECT.width() == view.getWidth();
    }

    private static void reportFailure(String mode, ViewGroup parent, Exception e) {
        String key = mode + parent.getClass().getName() + e.getClass().getName();
        if (!REPORTED_FAILURES.add(key)) return;
        String message = "Download button (" + mode + ") parent=" + parent.getClass().getName() + ": " + e;
        Logger.printInfo(() -> message);
        showDebugReport(parent.getContext(), message, e);
    }

    private static final Set<String> DEBUG_REPORTS_SHOWN = new java.util.HashSet<>();
    private static java.lang.ref.WeakReference<Activity> lastActivity;

    // Debug mode only: shows each distinct failure once, in a dialog that can be copied, since toasts are cut off.
    private static void showDebugReport(Context context, String message, Throwable e) {
        try {
            if (!Pref.pikoDebug() || !DEBUG_REPORTS_SHOWN.add(message)) return;
            Activity activity = lastActivity != null ? lastActivity.get() : null;
            if (activity != null && !activity.isFinishing()) context = activity;

            java.io.StringWriter trace = new java.io.StringWriter();
            if (e != null) e.printStackTrace(new java.io.PrintWriter(trace));
            String report = message
                    + "\n\nIds found: " + (RESOLVED_NAMES.isEmpty() ? "none" : String.join(", ", RESOLVED_NAMES))
                    + "\n\n" + trace;

            android.widget.TextView text = new android.widget.TextView(context);
            text.setText(report);
            text.setTextIsSelectable(true);
            text.setTextSize(11);
            int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
            text.setPadding(padding, padding, padding, padding);
            android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.addView(text);

            new android.app.AlertDialog.Builder(context)
                    .setTitle("Download button debug")
                    .setView(scroll)
                    .setPositiveButton("Copy", (d, w) -> {
                        Utils.setClipboard(report);
                        Utils.showToastShort(str("piko_copied"));
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "DownloadButtonPatch debug report failure", ex);
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
                if (Pref.pikoDebug()) {
                    showDebugReport(button.getContext(), "Download button: no media found (" + surface + ").\n\n"
                            + describeTree(ancestor(anchor, 3)), null);
                }
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
            showDebugReport(button.getContext(), "Download button click failure: " + e, e);
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
        // The other buttons of the row (the reels "More" button opens a menu for this media).
        if (anchor.getParent() instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) anchor.getParent();
            for (int i = row.getChildCount() - 1; i >= 0; i--) {
                View sibling = row.getChildAt(i);
                if (sibling != anchor) addSeed(seeds, getOnClickListener(sibling));
            }
        }
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
        // Several media can be reachable (a reels page can hold controllers of the pages next to it),
        // so collect a few and keep the one whose author is shown on the same page.
        List<Object> candidates = new ArrayList<>();
        for (Object seed : seeds) {
            Object found = searchFrom(seed, media, reelItem, visited, userSession);
            if (found != null && !containsSame(candidates, found)) candidates.add(found);
            if (candidates.size() >= MAX_CANDIDATES) break;
        }
        Object result = pickShownMedia(candidates, anchor, userSession[0]);

        if (result == null && Pref.pikoDebug()) {
            Logger.printInfo(() -> "DownloadButtonPatch no media, seeds=" + seeds.size() + " visited=" + visited.size());
        }
        return new Object[]{result, userSession[0]};
    }

    private static final int MAX_CANDIDATES = 6;

    private static boolean containsSame(List<Object> list, Object obj) {
        for (Object item : list) {
            if (item == obj) return true;
        }
        return false;
    }

    private static Object pickShownMedia(List<Object> candidates, View anchor, UserSession userSession) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        Set<String> texts = new java.util.HashSet<>();
        collectTexts(pageOf(anchor), 0, texts);
        for (Object candidate : candidates) {
            try {
                String username = new MediaData(candidate, userSession).getUserData().getUsername();
                if (username != null && texts.contains(username.toLowerCase())) return candidate;
            } catch (Exception ignored) {
            }
        }
        return candidates.get(0);
    }

    // The item of the list (feed row or reels page) that holds the view.
    private static View pageOf(View view) {
        View current = view;
        while (current.getParent() instanceof View && !isListView((View) current.getParent())) {
            current = (View) current.getParent();
        }
        return current;
    }

    private static void collectTexts(View view, int depth, Set<String> out) {
        if (depth > 25 || out.size() > 300) return;
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.length() > 0) {
                // Usernames can carry a verified badge or a separator after them.
                String value = text.toString().trim().toLowerCase();
                out.add(value);
                int space = value.indexOf(' ');
                if (space > 0) out.add(value.substring(0, space));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), depth + 1, out);
        }
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
        if (obj instanceof Collection || obj instanceof Map) return;
        if (obj instanceof Object[]) {
            // Small arrays only, like the event handlers Litho keeps per view.
            Object[] array = (Object[]) obj;
            if (array.length <= 8) {
                for (Object item : array) {
                    if (item != null) out.add(item);
                }
            }
            return;
        }
        if (obj.getClass().isArray()) return;
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
