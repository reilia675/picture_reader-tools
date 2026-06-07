package com.reilia.picshelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PICK_IMAGES = 1001;
    private static final int REQ_CONFIRM_DELETE = 1002;
    private static final int REQ_PICK_FOLDER = 1003;
    private static final int REQ_PICK_BACKGROUND = 1004;
    private static final String PREFS = "pic_shelf";
    private static final String KEY_GROUPS = "groups";
    private static final String KEY_CATEGORIES = "categories";
    private static final String KEY_CURRENT_CATEGORY = "current_category";
    private static final String KEY_THEME = "theme";
    private static final String KEY_HOME_BACKGROUND = "home_background";
    private static final String KEY_HOME_BACKGROUND_SOURCE = "home_background_source";
    private static final String KEY_HOME_BACKGROUND_BLUR = "home_background_blur";
    private static final String PRIVATE_IMAGE_DIR = "private_images";
    private static final String BACKGROUND_DIR = "backgrounds";
    private static final String BACKGROUND_FILE = "home_background.jpg";
    private static final String BACKGROUND_SOURCE_FILE = "home_background_source.jpg";
    private static final int DEFAULT_HOME_BLUR_LEVEL = 1;
    private static final int MAX_HOME_BLUR_LEVEL = 20;
    private static final String THEME_MINIMAL = "minimal";
    private static final String THEME_BLUE = "blue";
    private static final String THEME_RED = "red";

    private final List<Category> categories = new ArrayList<>();
    private final List<Uri> pendingDeleteUris = new ArrayList<>();
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private AlertDialog importDialog;
    private AlertDialog categoryDialog;
    private PopupWindow drawerWindow;
    private FrameLayout viewerImageLayer;
    private ImageView viewerImage;
    private ImageView viewerNextImage;
    private TextView viewerCounter;
    private TextView importStatusText;
    private ProgressBar importProgressBar;
    private String themeMode = THEME_MINIMAL;
    private String homeBackgroundUri = "";
    private String homeBackgroundSourceUri = "";
    private int homeBackgroundBlurLevel = DEFAULT_HOME_BLUR_LEVEL;
    private int currentCategoryIndex = 0;
    private int currentGroupIndex = -1;
    private int currentImageIndex = -1;
    private int renderToken = 0;
    private int viewerDragTargetIndex = -1;
    private float viewerTouchStartX = 0;
    private float viewerTouchStartY = 0;
    private boolean viewerDragging = false;
    private boolean viewerSwitchAnimating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSettings();
        loadGroups();
        recoverPrivateFiles();
        showHome();
    }

    private void showHome() {
        currentGroupIndex = -1;
        currentImageIndex = -1;
        resetViewerState();
        renderToken++;
        ThemeSpec theme = theme();
        Category category = activeCategory();
        LinearLayout root = verticalRoot(theme);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(2), dp(2), dp(2), dp(6));

        Button menu = button("三");
        menu.setTextSize(20);
        menu.setOnClickListener(v -> showNavigationDrawer());

        TextView title = title("图架");
        title.setPadding(dp(10), 0, dp(10), 0);

        topBar.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(48)));
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(topBar);

        TextView subtitle = smallText("当前分类: " + category.name + (category.hasPassword() ? "  ·  已设密码" : ""));
        subtitle.setPadding(dp(12), 0, dp(12), dp(8));
        Button add = button("+ 新建分组");
        add.setOnClickListener(v -> {
            if (activeCategory().isLocked()) {
                promptUnlockCategory(currentCategoryIndex);
            } else {
                promptNewGroup();
            }
        });
        Button background = button("首页背景");
        background.setOnClickListener(v -> showBackgroundActions());
        root.addView(subtitle);

        LinearLayout themeBar = new LinearLayout(this);
        themeBar.setOrientation(LinearLayout.HORIZONTAL);
        themeBar.setPadding(dp(2), dp(4), dp(2), dp(6));
        themeBar.addView(themeButton("极简", THEME_MINIMAL), new LinearLayout.LayoutParams(0, dp(44), 1));
        themeBar.addView(themeButton("蓝夜", THEME_BLUE), new LinearLayout.LayoutParams(0, dp(44), 1));
        themeBar.addView(themeButton("红影", THEME_RED), new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(themeBar);

        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setPadding(dp(2), dp(2), dp(2), dp(10));
        LinearLayout.LayoutParams leftAction = new LinearLayout.LayoutParams(0, dp(48), 1);
        leftAction.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams rightAction = new LinearLayout.LayoutParams(0, dp(48), 1);
        rightAction.setMargins(dp(5), 0, 0, 0);
        actionBar.addView(add, leftAction);
        actionBar.addView(background, rightAction);
        root.addView(actionBar);

        if (category.isLocked()) {
            TextView locked = smallText("这个分类已上锁。解锁后才能查看其中的图集。");
            locked.setGravity(Gravity.CENTER);
            locked.setPadding(dp(18), dp(42), dp(18), dp(18));
            Button unlock = button("解锁分类");
            unlock.setOnClickListener(v -> promptUnlockCategory(currentCategoryIndex));
            root.addView(locked);
            root.addView(unlock);
        } else if (category.groups.isEmpty()) {
            TextView empty = smallText("这个分类还没有图集。先新建一个分组，再移动导入手机本地图片。");
            empty.setPadding(dp(18), dp(36), dp(18), dp(18));
            root.addView(empty);
        } else {
            LinearLayout shelf = new LinearLayout(this);
            shelf.setOrientation(LinearLayout.VERTICAL);
            shelf.setPadding(0, dp(8), 0, dp(26));
            int cardWidth = Math.max(dp(86), (getResources().getDisplayMetrics().widthPixels - dp(64)) / 3);
            for (int rowStart = 0; rowStart < category.groups.size(); rowStart += 3) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                int rowEnd = Math.min(rowStart + 3, category.groups.size());
                for (int i = rowStart; i < rowEnd; i++) {
                    final int index = i;
                    Group group = category.groups.get(i);
                    View item = shelfItem(group, index, theme, cardWidth);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            cardWidth,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    params.setMargins(dp(4), dp(6), dp(4), dp(10));
                    row.addView(item, params);
                }
                shelf.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
            root.addView(shelf);
        }

        setHomeContentView(root, theme);
    }

    private void showGroup(int index) {
        if (activeCategory().isLocked()) {
            promptUnlockCategory(currentCategoryIndex);
            return;
        }
        if (index < 0 || index >= activeGroups().size()) {
            showHome();
            return;
        }
        currentGroupIndex = index;
        renderToken++;
        Group group = activeGroups().get(index);
        ThemeSpec theme = theme();

        LinearLayout root = verticalRoot(theme);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(12), dp(12), dp(8));

        Button back = button("<");
        back.setOnClickListener(v -> showHome());
        TextView title = title(group.name);

        bar.addView(back, new LinearLayout.LayoutParams(dp(54), dp(48)));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        TextView meta = smallText(group.images.size() + " 张  ·  " + formatBytes(groupSizeBytes(group)));
        meta.setPadding(dp(18), 0, dp(18), dp(8));
        root.addView(meta);

        LinearLayout importBar = new LinearLayout(this);
        importBar.setOrientation(LinearLayout.HORIZONTAL);
        importBar.setPadding(dp(12), dp(0), dp(12), dp(10));
        Button pickImages = button("+ 选图片");
        pickImages.setOnClickListener(v -> pickImages());
        Button pickFolder = button("+ 文件夹");
        pickFolder.setOnClickListener(v -> pickFolder());
        Button clearCover = button("默认封面");
        clearCover.setOnClickListener(v -> {
            group.coverUri = "";
            saveGroups();
            showGroup(index);
        });
        LinearLayout.LayoutParams firstImport = new LinearLayout.LayoutParams(0, dp(48), 1);
        firstImport.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams middleImport = new LinearLayout.LayoutParams(0, dp(48), 1);
        middleImport.setMargins(dp(5), 0, dp(5), 0);
        LinearLayout.LayoutParams lastImport = new LinearLayout.LayoutParams(0, dp(48), 1);
        lastImport.setMargins(dp(5), 0, 0, 0);
        importBar.addView(pickImages, firstImport);
        importBar.addView(pickFolder, middleImport);
        importBar.addView(clearCover, lastImport);
        root.addView(importBar);

        if (group.images.isEmpty()) {
            TextView empty = smallText("这个分组还没有图片。可以选择多张图片，也可以直接导入一个文件夹。");
            empty.setPadding(dp(18), dp(36), dp(18), dp(18));
            root.addView(empty);
        } else {
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(3);
            grid.setPadding(0, dp(8), 0, dp(24));

            int size = Math.max(dp(82), (getResources().getDisplayMetrics().widthPixels - dp(56)) / 3);
            for (int i = 0; i < group.images.size(); i++) {
                final int imageIndex = i;
                final String imageValue = group.images.get(i);
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(Color.rgb(230, 235, 233));
                image.setTag(imageValue);
                loadThumbnail(image, Uri.parse(imageValue), size);
                image.setOnClickListener(v -> showViewer(index, imageIndex));
                image.setOnLongClickListener(v -> {
                    showImageActions(index, imageIndex);
                    return true;
                });

                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width = size;
                params.height = size;
                params.setMargins(dp(4), dp(4), dp(4), dp(4));
                grid.addView(image, params);
            }
            root.addView(grid);
        }

        setContentView(wrapScroll(root, theme));
        animateIn(root);
    }

    private void showViewer(int groupIndex, int imageIndex) {
        if (groupIndex < 0 || groupIndex >= activeGroups().size()) {
            showHome();
            return;
        }
        currentGroupIndex = groupIndex;
        currentImageIndex = imageIndex;
        resetViewerState();
        Group group = activeGroups().get(groupIndex);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        viewerImageLayer = new FrameLayout(this);
        viewerImage = viewerImageView();
        viewerNextImage = viewerImageView();
        viewerNextImage.setVisibility(View.GONE);
        viewerImage.setImageURI(Uri.parse(group.images.get(imageIndex)));
        viewerImageLayer.addView(viewerImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        viewerImageLayer.addView(viewerNextImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(viewerImageLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        Button close = button("<");
        close.setTextColor(Color.WHITE);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(v -> showGroup(groupIndex));
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(72), dp(58));
        closeParams.gravity = Gravity.START | Gravity.TOP;
        root.addView(close, closeParams);

        viewerCounter = new TextView(this);
        viewerCounter.setTextColor(Color.WHITE);
        viewerCounter.setTextSize(15);
        viewerCounter.setGravity(Gravity.CENTER);
        viewerCounter.setBackgroundColor(Color.argb(120, 0, 0, 0));
        FrameLayout.LayoutParams counterParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        counterParams.gravity = Gravity.BOTTOM;
        root.addView(viewerCounter, counterParams);
        updateViewerCounter();

        setContentView(root);
        animateIn(root);
    }

    private ImageView viewerImageView() {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        return image;
    }

    private void resetViewerState() {
        viewerImageLayer = null;
        viewerImage = null;
        viewerNextImage = null;
        viewerCounter = null;
        viewerDragTargetIndex = -1;
        viewerDragging = false;
        viewerSwitchAnimating = false;
    }

    private void updateViewerCounter() {
        if (viewerCounter == null || currentGroupIndex < 0) {
            return;
        }
        Group group = activeGroups().get(currentGroupIndex);
        viewerCounter.setText((currentImageIndex + 1) + " / " + group.images.size());
    }

    private boolean handleViewerTouch(MotionEvent event) {
        if (viewerImageLayer == null || viewerImage == null || viewerNextImage == null || currentGroupIndex < 0) {
            return false;
        }
        if (viewerSwitchAnimating) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            viewerTouchStartX = event.getX();
            viewerTouchStartY = event.getY();
            viewerDragging = false;
            viewerDragTargetIndex = -1;
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - viewerTouchStartX;
            float dy = event.getY() - viewerTouchStartY;
            if (!viewerDragging && Math.abs(dx) > dp(8) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                viewerDragging = true;
                if (viewerImageLayer.getParent() != null) {
                    viewerImageLayer.getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (viewerDragging) {
                updateViewerDrag(dx);
                return true;
            }
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (viewerDragging) {
                finishViewerDrag(event.getX() - viewerTouchStartX);
                return true;
            }
            resetViewerDrag();
        }
        return false;
    }

    private void updateViewerDrag(float dx) {
        Group group = activeGroups().get(currentGroupIndex);
        int width = Math.max(1, viewerImageLayer.getWidth());
        int target = currentImageIndex + (dx < 0 ? 1 : -1);
        if (target < 0 || target >= group.images.size()) {
            viewerDragTargetIndex = -1;
            viewerNextImage.setVisibility(View.GONE);
            viewerImage.setTranslationX(dx * 0.22f);
            return;
        }
        if (viewerDragTargetIndex != target) {
            viewerDragTargetIndex = target;
            viewerNextImage.setImageURI(Uri.parse(group.images.get(target)));
            viewerNextImage.setVisibility(View.VISIBLE);
        }
        viewerImage.setTranslationX(dx);
        viewerNextImage.setTranslationX((dx < 0 ? width : -width) + dx);
    }

    private void finishViewerDrag(float dx) {
        int width = Math.max(1, viewerImageLayer.getWidth());
        float threshold = Math.max(dp(70), width * 0.22f);
        if (viewerDragTargetIndex >= 0 && Math.abs(dx) >= threshold) {
            commitViewerDrag(width);
        } else {
            cancelViewerDrag(width);
        }
    }

    private void commitViewerDrag(int width) {
        final int target = viewerDragTargetIndex;
        final int exitX = target > currentImageIndex ? -width : width;
        viewerSwitchAnimating = true;
        viewerImage.animate().translationX(exitX).setDuration(170).start();
        viewerNextImage.animate()
                .translationX(0f)
                .setDuration(170)
                .withEndAction(() -> {
                    Group group = activeGroups().get(currentGroupIndex);
                    currentImageIndex = target;
                    viewerImage.setImageURI(Uri.parse(group.images.get(currentImageIndex)));
                    viewerImage.setTranslationX(0f);
                    viewerNextImage.setVisibility(View.GONE);
                    viewerNextImage.setTranslationX(0f);
                    viewerDragTargetIndex = -1;
                    viewerDragging = false;
                    viewerSwitchAnimating = false;
                    updateViewerCounter();
                })
                .start();
    }

    private void cancelViewerDrag(int width) {
        final int target = viewerDragTargetIndex;
        viewerSwitchAnimating = true;
        if (target >= 0) {
            int nextX = target > currentImageIndex ? width : -width;
            viewerImage.animate().translationX(0f).setDuration(150).start();
            viewerNextImage.animate()
                    .translationX(nextX)
                    .setDuration(150)
                    .withEndAction(this::resetViewerDrag)
                    .start();
        } else {
            viewerImage.animate()
                    .translationX(0f)
                    .setDuration(130)
                    .withEndAction(this::resetViewerDrag)
                    .start();
        }
    }

    private void resetViewerDrag() {
        if (viewerImage != null) {
            viewerImage.setTranslationX(0f);
        }
        if (viewerNextImage != null) {
            viewerNextImage.setTranslationX(0f);
            viewerNextImage.setVisibility(View.GONE);
        }
        viewerDragTargetIndex = -1;
        viewerDragging = false;
        viewerSwitchAnimating = false;
    }

    private void loadThumbnail(ImageView target, Uri uri, int targetSize) {
        final String expected = uri.toString();
        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = decodeScaledBitmap(uri, targetSize, targetSize);
            runOnUiThread(() -> {
                Object tag = target.getTag();
                if (!expected.equals(tag)) {
                    return;
                }
                if (bitmap != null) {
                    target.setImageBitmap(bitmap);
                } else {
                    target.setBackgroundColor(Color.rgb(210, 216, 214));
                }
            });
        });
    }

    private Bitmap decodeScaledBitmap(Uri uri, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    return null;
                }
                BitmapFactory.decodeStream(input, null, bounds);
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSizeFor(bounds, reqWidth, reqHeight);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (IOException | SecurityException ignored) {
            return null;
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    private int sampleSizeFor(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int sampleSize = 1;
        if (height <= 0 || width <= 0) {
            return sampleSize;
        }
        while ((height / sampleSize) > reqHeight * 2 || (width / sampleSize) > reqWidth * 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private void setHomeContentView(LinearLayout content, ThemeSpec theme) {
        if (!hasHomeBackground()) {
            setContentView(wrapScroll(content, theme));
            animateIn(content);
            return;
        }

        content.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout frame = new FrameLayout(this);
        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setImageURI(Uri.parse(homeBackgroundUri));
        frame.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        View overlay = new View(this);
        overlay.setBackgroundColor(theme.homeOverlayColor);
        frame.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        frame.addView(wrapScroll(content, theme), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(frame);
        animateIn(content);
    }

    private View groupCard(Group group, int index, ThemeSpec theme) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(rounded(theme.cardColor, dp(8), theme.borderColor, dp(1)));
        card.setOnClickListener(v -> showGroup(index));
        card.setOnLongClickListener(v -> {
            confirmDeleteGroup(index);
            return true;
        });

        View cover = coverView(group, dp(74), theme);
        card.addView(cover, new LinearLayout.LayoutParams(dp(74), dp(74)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(group.name);
        name.setTextSize(18);
        name.setTextColor(theme.textColor);
        name.setGravity(Gravity.CENTER_VERTICAL);

        TextView meta = new TextView(this);
        meta.setText(group.images.size() + " 张  ·  " + formatBytes(groupSizeBytes(group)));
        meta.setTextSize(14);
        meta.setTextColor(theme.mutedColor);
        meta.setPadding(0, dp(6), 0, 0);

        text.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        text.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(10), dp(6), dp(10), dp(8));
        card.setLayoutParams(params);
        return card;
    }

    private View coverView(Group group, int size, ThemeSpec theme) {
        FrameLayout cover = new FrameLayout(this);
        cover.setBackground(rounded(theme.defaultCoverColor, dp(8), theme.borderColor, dp(1)));
        String coverUri = validCoverUri(group);
        if (!coverUri.isEmpty()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setTag(coverUri);
            cover.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            loadThumbnail(image, Uri.parse(coverUri), size);
        } else {
            TextView label = new TextView(this);
            label.setText("图架");
            label.setTextColor(theme.textColor);
            label.setTextSize(15);
            label.setGravity(Gravity.CENTER);
            cover.addView(label, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
        return cover;
    }

    private Button themeButton(String text, String mode) {
        ThemeSpec theme = theme();
        Button view = button(text);
        boolean selected = mode.equals(themeMode);
        view.setTextColor(selected ? theme.activeThemeTextColor : theme.buttonTextColor);
        view.setBackground(rounded(selected ? theme.accentColor : theme.buttonColor, dp(7), theme.borderColor, dp(1)));
        view.setOnClickListener(v -> {
            themeMode = mode;
            saveSettings();
            showHome();
        });
        return view;
    }

    private View shelfItem(Group group, int index, ThemeSpec theme, int width) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(4), dp(4), dp(4), dp(4));
        item.setBackground(rounded(theme.cardColor, dp(8), theme.borderColor, dp(1)));
        item.setOnClickListener(v -> {
            animateTap(item);
            item.postDelayed(() -> showGroup(index), 80);
        });
        item.setOnLongClickListener(v -> {
            confirmDeleteGroup(index);
            return true;
        });

        View cover = coverView(group, width, theme);
        item.addView(cover, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(dp(112), Math.round(width * 1.28f))
        ));

        TextView name = new TextView(this);
        name.setText(group.name);
        name.setTextColor(theme.textColor);
        name.setTextSize(13);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setPadding(dp(3), dp(7), dp(3), 0);

        TextView meta = new TextView(this);
        meta.setText(group.images.size() + "张 · " + formatBytes(groupSizeBytes(group)));
        meta.setTextColor(theme.mutedColor);
        meta.setTextSize(11);
        meta.setGravity(Gravity.CENTER);
        meta.setMaxLines(1);
        meta.setPadding(dp(2), dp(3), dp(2), dp(4));

        item.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        item.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        item.setAlpha(0f);
        item.setTranslationY(dp(10));
        item.animate().alpha(1f).translationY(0f).setDuration(180).setStartDelay((index % 6) * 26L).start();
        return item;
    }

    private void showNavigationDrawer() {
        dismissNavigationDrawer();
        ThemeSpec theme = theme();
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.TRANSPARENT);

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(145, 0, 0, 0));
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(theme.cardColor);
        panel.setPadding(dp(18), dp(26), dp(18), dp(18));
        int drawerWidth = Math.min(dp(330), Math.round(getResources().getDisplayMetrics().widthPixels * 0.78f));

        TextView header = title("图架");
        header.setPadding(0, 0, 0, dp(4));
        TextView current = smallText("当前分类: " + activeCategory().name);
        current.setPadding(0, 0, 0, dp(18));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.addView(current, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        panel.addView(drawerItem("主页", true, () -> showHome()));
        panel.addView(drawerItem("分类", false, () -> showCategoryList()));
        panel.addView(drawerItem("设置", false, () -> showSettings()));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                drawerWidth,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        panelParams.gravity = Gravity.START;
        overlay.addView(panel, panelParams);
        scrim.setOnClickListener(v -> closeNavigationDrawer(panel, null));

        drawerWindow = new PopupWindow(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        drawerWindow.setOutsideTouchable(true);
        drawerWindow.setOnDismissListener(() -> drawerWindow = null);
        drawerWindow.showAtLocation(getWindow().getDecorView(), Gravity.NO_GRAVITY, 0, 0);
        panel.setTranslationX(-drawerWidth);
        panel.animate().translationX(0f).setDuration(180).start();
    }

    private Button drawerItem(String text, boolean selected, Runnable action) {
        ThemeSpec theme = theme();
        Button item = button((selected ? "●  " : "   ") + text);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        item.setTextSize(18);
        item.setTextColor(selected ? theme.activeThemeTextColor : theme.textColor);
        item.setBackground(rounded(selected ? theme.accentColor : Color.TRANSPARENT, dp(7), Color.TRANSPARENT, 0));
        item.setPadding(dp(18), dp(4), dp(18), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
        );
        params.setMargins(0, dp(4), 0, dp(4));
        item.setLayoutParams(params);
        item.setOnClickListener(v -> closeNavigationDrawer((View) item.getParent(), action));
        return item;
    }

    private void closeNavigationDrawer(View panel, Runnable afterClose) {
        if (panel == null) {
            dismissNavigationDrawer();
            if (afterClose != null) {
                afterClose.run();
            }
            return;
        }
        panel.animate()
                .translationX(-Math.max(panel.getWidth(), dp(260)))
                .setDuration(150)
                .withEndAction(() -> {
                    dismissNavigationDrawer();
                    if (afterClose != null) {
                        afterClose.run();
                    }
                })
                .start();
    }

    private void dismissNavigationDrawer() {
        if (drawerWindow != null && drawerWindow.isShowing()) {
            drawerWindow.dismiss();
        }
        drawerWindow = null;
    }

    private void showCategoryList() {
        dismissCategoryDialog();
        ThemeSpec theme = theme();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));

        for (int i = 0; i < categories.size(); i++) {
            final int index = i;
            Category category = categories.get(i);
            Button item = button((index == currentCategoryIndex ? "✓ " : "") + category.name
                    + "  ·  " + category.groups.size() + " 个图集"
                    + (category.hasPassword() ? "  锁" : ""));
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setOnClickListener(v -> {
                dismissCategoryDialog();
                selectCategory(index);
            });
            item.setOnLongClickListener(v -> {
                dismissCategoryDialog();
                showCategoryActions(index);
                return true;
            });
            panel.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(50)
            ));
        }

        Button add = button("+ 新建分类");
        add.setOnClickListener(v -> {
            dismissCategoryDialog();
            promptNewCategory();
        });
        panel.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        ));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.backgroundColor);
        scroll.addView(panel);

        categoryDialog = new AlertDialog.Builder(this)
                .setTitle("分类")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        categoryDialog.setOnDismissListener(dialog -> categoryDialog = null);
        categoryDialog.show();
    }

    private void dismissCategoryDialog() {
        if (categoryDialog != null && categoryDialog.isShowing()) {
            categoryDialog.dismiss();
        }
        categoryDialog = null;
    }

    private void selectCategory(int index) {
        if (index < 0 || index >= categories.size()) {
            return;
        }
        Category category = categories.get(index);
        if (category.hasPassword() && !category.unlocked) {
            promptUnlockCategory(index);
            return;
        }
        currentCategoryIndex = index;
        currentGroupIndex = -1;
        currentImageIndex = -1;
        saveSettings();
        showHome();
    }

    private void promptNewCategory() {
        EditText input = new EditText(this);
        input.setHint("分类名");
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("新建分类")
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "新分类";
                    }
                    Category category = new Category(String.valueOf(System.currentTimeMillis()), name);
                    categories.add(category);
                    currentCategoryIndex = categories.size() - 1;
                    saveGroups();
                    saveSettings();
                    showHome();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCategoryActions(int index) {
        if (index < 0 || index >= categories.size()) {
            return;
        }
        Category category = categories.get(index);
        String[] items = category.hasPassword()
                ? new String[]{"重命名", "修改密码", "清除密码"}
                : new String[]{"重命名", "设置密码"};
        new AlertDialog.Builder(this)
                .setTitle(category.name)
                .setItems(items, (dialog, which) -> {
                    String item = items[which];
                    if ("重命名".equals(item)) {
                        promptRenameCategory(index);
                    } else if ("清除密码".equals(item)) {
                        category.passwordHash = "";
                        category.unlocked = true;
                        saveGroups();
                        showHome();
                    } else {
                        promptSetCategoryPassword(index);
                    }
                })
                .show();
    }

    private void promptRenameCategory(int index) {
        Category category = categories.get(index);
        EditText input = new EditText(this);
        input.setText(category.name);
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("重命名分类")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        category.name = name;
                        saveGroups();
                        showHome();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptSetCategoryPassword(int index) {
        Category category = categories.get(index);
        EditText input = new EditText(this);
        input.setHint("分类密码");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("设置分类密码")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String password = input.getText().toString();
                    if (password.isEmpty()) {
                        Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    category.passwordHash = hashPassword(password);
                    category.unlocked = true;
                    saveGroups();
                    showHome();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptUnlockCategory(int index) {
        if (index < 0 || index >= categories.size()) {
            return;
        }
        Category category = categories.get(index);
        EditText input = new EditText(this);
        input.setHint("输入密码");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("解锁「" + category.name + "」")
                .setView(input)
                .setPositiveButton("解锁", (dialog, which) -> {
                    if (category.passwordHash.equals(hashPassword(input.getText().toString()))) {
                        category.unlocked = true;
                        currentCategoryIndex = index;
                        saveSettings();
                        showHome();
                    } else {
                        Toast.makeText(this, "密码不正确", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showImageActions(int groupIndex, int imageIndex) {
        Group group = activeGroups().get(groupIndex);
        String image = group.images.get(imageIndex);
        boolean isCover = image.equals(group.coverUri);
        String[] items = isCover
                ? new String[]{"取消封面", "移出图片"}
                : new String[]{"设为封面", "移出图片"};
        new AlertDialog.Builder(this)
                .setTitle("图片操作")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        group.coverUri = isCover ? "" : image;
                        saveGroups();
                        showGroup(groupIndex);
                    } else {
                        confirmRemoveImage(groupIndex, imageIndex);
                    }
                })
                .show();
    }

    private String validCoverUri(Group group) {
        if (group.coverUri == null || group.coverUri.isEmpty()) {
            return "";
        }
        if (!group.images.contains(group.coverUri)) {
            return "";
        }
        File file = fileFromImageValue(group.coverUri);
        if (file != null && file.exists()) {
            return group.coverUri;
        }
        return "";
    }

    private long groupSizeBytes(Group group) {
        long total = 0;
        for (String image : group.images) {
            File file = fileFromImageValue(image);
            if (file != null && file.exists()) {
                total += file.length();
            }
        }
        return total;
    }

    private File fileFromImageValue(String value) {
        try {
            Uri uri = Uri.parse(value);
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                return new File(uri.getPath());
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private Category activeCategory() {
        if (categories.isEmpty()) {
            categories.add(new Category("default", "默认分类"));
            currentCategoryIndex = 0;
        }
        if (currentCategoryIndex < 0 || currentCategoryIndex >= categories.size()) {
            currentCategoryIndex = 0;
        }
        return categories.get(currentCategoryIndex);
    }

    private List<Group> activeGroups() {
        return activeCategory().groups;
    }

    private void animateIn(View view) {
        view.setAlpha(0f);
        view.setTranslationY(dp(10));
        view.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    private void animateTap(View view) {
        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(50).withEndAction(() ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
        ).start();
    }

    private String hashPassword(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return String.valueOf(value.hashCode());
        }
    }

    private boolean hasHomeBackground() {
        File file = fileFromImageValue(homeBackgroundUri);
        return file != null && file.exists();
    }

    private Bitmap blurBitmap(Bitmap input, int iterations) {
        Bitmap bitmap = input.copy(Bitmap.Config.ARGB_8888, true);
        int level = Math.max(0, Math.min(MAX_HOME_BLUR_LEVEL, iterations));
        if (level <= 0) {
            return bitmap;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        int[] temp = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int radius = 1 + (level / 2);
        int passes = 1 + (level / 6);
        for (int pass = 0; pass < passes; pass++) {
            boxBlurHorizontal(pixels, temp, width, height, radius);
            boxBlurVertical(temp, pixels, width, height, radius);
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private void boxBlurHorizontal(int[] input, int[] output, int width, int height, int radius) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                int count = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    int sx = x + dx;
                    if (sx < 0 || sx >= width) {
                        continue;
                    }
                    int color = input[y * width + sx];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                    count++;
                }
                output[y * width + x] = Color.argb(a / count, r / count, g / count, b / count);
            }
        }
    }

    private void boxBlurVertical(int[] input, int[] output, int width, int height, int radius) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                int count = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int sy = y + dy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    int color = input[sy * width + x];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                    count++;
                }
                output[y * width + x] = Color.argb(a / count, r / count, g / count, b / count);
            }
        }
    }

    private void moveViewer(int delta) {
        if (currentGroupIndex < 0) {
            return;
        }
        Group group = activeGroups().get(currentGroupIndex);
        int next = currentImageIndex + delta;
        if (next < 0 || next >= group.images.size()) {
            return;
        }
        showViewer(currentGroupIndex, next);
    }

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_IMAGES);
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    private void pickHomeBackground() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_BACKGROUND);
    }

    private void showSettings() {
        ThemeSpec theme = theme();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(4));

        TextView version = smallText("版本 " + appVersionLabel());
        version.setPadding(0, 0, 0, dp(8));
        panel.addView(version);

        TextView guide = smallText(
                "功能说明\n"
                        + "· 首页左上角“三”打开导览。\n"
                        + "· 分类可放多个图集，长按分类可重命名或设置密码。\n"
                        + "· 图集内可多选图片或导入文件夹；导入后图片会移入 App 私密空间。\n"
                        + "· 浏览图片时左右拖动，拖过一定距离后切换。"
        );
        guide.setPadding(0, 0, 0, dp(12));
        panel.addView(guide);

        Button changeBackground = button(hasHomeBackground() ? "更换首页背景" : "设置首页背景");
        panel.addView(changeBackground, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        Button clearBackground = button("清除首页背景");
        clearBackground.setEnabled(hasHomeBackground());
        panel.addView(clearBackground, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        TextView blurLabel = smallText("首页背景虚化程度: " + homeBackgroundBlurLevel);
        blurLabel.setPadding(0, dp(12), 0, dp(2));
        panel.addView(blurLabel);

        SeekBar blur = new SeekBar(this);
        blur.setMax(MAX_HOME_BLUR_LEVEL);
        blur.setProgress(homeBackgroundBlurLevel);
        blur.setEnabled(hasHomeBackground());
        blur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                blurLabel.setText("首页背景虚化程度: " + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        panel.addView(blur, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        if (!hasHomeBackground()) {
                TextView hint = smallText("还没有首页背景，选择一张图片后虚化程度会生效。0 为不虚化。");
            hint.setPadding(0, 0, 0, dp(4));
            panel.addView(hint);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.cardColor);
        scroll.addView(panel);

        final AlertDialog[] dialogRef = new AlertDialog[1];
        changeBackground.setOnClickListener(v -> {
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
            pickHomeBackground();
        });
        clearBackground.setOnClickListener(v -> {
            if (dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
            clearHomeBackground();
        });

        dialogRef[0] = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("应用", (dialog, which) -> {
                    homeBackgroundBlurLevel = blur.getProgress();
                    saveSettings();
                    if (hasHomeBackground()) {
                        applyHomeBackgroundBlurLevel();
                    } else {
                        showHome();
                    }
                })
                .setNegativeButton("关闭", null)
                .create();
        dialogRef[0].show();
    }

    private String appVersionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception ignored) {
            return "未知版本";
        }
    }

    private void showBackgroundActions() {
        if (!hasHomeBackground()) {
            pickHomeBackground();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("首页背景")
                .setItems(new String[]{"更换背景", "调整虚化程度", "清除背景"}, (dialog, which) -> {
                    if (which == 0) {
                        pickHomeBackground();
                    } else if (which == 1) {
                        showSettings();
                    } else {
                        clearHomeBackground();
                    }
                })
                .show();
    }

    private void saveHomeBackground(Uri source) {
        try {
            try {
                getContentResolver().takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Temporary access is enough because the blurred copy is stored privately.
            }

            Bitmap bitmap = decodeScaledBitmap(source, 720, 720);
            if (bitmap == null) {
                Toast.makeText(this, "背景图片读取失败", Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = new File(getFilesDir(), BACKGROUND_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "背景保存失败", Toast.LENGTH_SHORT).show();
                return;
            }
            File sourceTarget = new File(dir, BACKGROUND_SOURCE_FILE);
            try (OutputStream output = new FileOutputStream(sourceTarget)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output);
            }
            homeBackgroundSourceUri = Uri.fromFile(sourceTarget).toString();
            writeHomeBackground(bitmap);
            saveSettings();
            Toast.makeText(this, "首页背景已更新", Toast.LENGTH_SHORT).show();
            showHome();
        } catch (IOException | SecurityException ignored) {
            Toast.makeText(this, "背景保存失败", Toast.LENGTH_SHORT).show();
        } catch (OutOfMemoryError ignored) {
            Toast.makeText(this, "图片过大，背景处理失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyHomeBackgroundBlurLevel() {
        try {
            File source = fileFromImageValue(homeBackgroundSourceUri);
            if (source == null || !source.exists()) {
                source = fileFromImageValue(homeBackgroundUri);
            }
            if (source == null || !source.exists()) {
                Toast.makeText(this, "没有可处理的首页背景", Toast.LENGTH_SHORT).show();
                showHome();
                return;
            }
            Bitmap bitmap = decodeScaledBitmap(Uri.fromFile(source), 720, 720);
            if (bitmap == null) {
                Toast.makeText(this, "背景图片读取失败", Toast.LENGTH_SHORT).show();
                showHome();
                return;
            }
            writeHomeBackground(bitmap);
            saveSettings();
            Toast.makeText(this, "虚化程度已更新", Toast.LENGTH_SHORT).show();
            showHome();
        } catch (IOException | SecurityException ignored) {
            Toast.makeText(this, "背景处理失败", Toast.LENGTH_SHORT).show();
            showHome();
        } catch (OutOfMemoryError ignored) {
            Toast.makeText(this, "图片过大，背景处理失败", Toast.LENGTH_SHORT).show();
            showHome();
        }
    }

    private void writeHomeBackground(Bitmap bitmap) throws IOException {
        File dir = new File(getFilesDir(), BACKGROUND_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("background dir");
        }
        Bitmap outputBitmap = blurBitmap(bitmap, homeBackgroundBlurLevel);
        File target = new File(dir, BACKGROUND_FILE);
        try (OutputStream output = new FileOutputStream(target)) {
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 86, output);
        }
        homeBackgroundUri = Uri.fromFile(target).toString();
    }

    private void clearHomeBackground() {
        deletePrivateFile(homeBackgroundUri);
        deletePrivateFile(homeBackgroundSourceUri);
        homeBackgroundUri = "";
        homeBackgroundSourceUri = "";
        saveSettings();
        showHome();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONFIRM_DELETE) {
            pendingDeleteUris.clear();
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "原相册图片已删除", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "图片已移入私密空间；原相册图片未删除。", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQ_PICK_BACKGROUND) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                saveHomeBackground(data.getData());
            }
            return;
        }
        if ((requestCode != REQ_PICK_IMAGES && requestCode != REQ_PICK_FOLDER)
                || resultCode != RESULT_OK
                || currentGroupIndex < 0
                || data == null) {
            return;
        }

        startImport(requestCode, currentCategoryIndex, currentGroupIndex, data);
    }

    private void startImport(int requestCode, int categoryIndex, int groupIndex, Intent data) {
        showImportProgress("正在准备导入…");
        new Thread(() -> {
            ImportResult total;
            synchronized (categories) {
                if (categoryIndex < 0 || categoryIndex >= categories.size()) {
                    return;
                }
                Category category = categories.get(categoryIndex);
                if (groupIndex < 0 || groupIndex >= category.groups.size()) {
                    return;
                }
                Group group = category.groups.get(groupIndex);
                pendingDeleteUris.clear();
                if (requestCode == REQ_PICK_FOLDER) {
                    total = importFolder(group, data.getData(), data.getFlags());
                } else {
                    total = importPickedImages(group, data);
                }
                saveGroups();
            }

            runOnUiThread(() -> {
                hideImportProgress();
                String message = "已移入 " + total.added + " 张";
                if (total.deleted > 0) {
                    message += "，已删除原图 " + total.deleted + " 张";
                }
                if (total.failed > 0) {
                    message += "，失败 " + total.failed + " 张";
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                if (categoryIndex >= 0 && categoryIndex < categories.size()
                        && groupIndex >= 0
                        && groupIndex < categories.get(categoryIndex).groups.size()) {
                    currentCategoryIndex = categoryIndex;
                    showGroup(groupIndex);
                }
                requestSystemDeleteIfNeeded();
            });
        }).start();
    }

    private void showImportProgress(String message) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(18), dp(22), dp(10));

        importStatusText = new TextView(this);
        importStatusText.setText(message);
        importStatusText.setTextSize(16);
        importStatusText.setTextColor(Color.rgb(18, 33, 43));
        importStatusText.setPadding(0, 0, 0, dp(12));

        importProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importProgressBar.setIndeterminate(true);
        importProgressBar.setMax(100);

        panel.addView(importStatusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.addView(importProgressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(12)
        ));

        importDialog = new AlertDialog.Builder(this)
                .setTitle("导入图片")
                .setView(panel)
                .setCancelable(false)
                .create();
        importDialog.show();
    }

    private void updateImportMessage(String message) {
        runOnUiThread(() -> {
            if (importStatusText != null) {
                importStatusText.setText(message);
            }
            if (importProgressBar != null) {
                importProgressBar.setIndeterminate(true);
            }
        });
    }

    private void updateImportProgress(int done, int total) {
        runOnUiThread(() -> {
            if (importStatusText != null) {
                importStatusText.setText("正在导入 " + done + " / " + total);
            }
            if (importProgressBar != null) {
                importProgressBar.setIndeterminate(false);
                importProgressBar.setMax(Math.max(total, 1));
                importProgressBar.setProgress(done);
            }
        });
    }

    private void hideImportProgress() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }
        importDialog = null;
        importStatusText = null;
        importProgressBar = null;
    }

    private ImportResult importPickedImages(Group group, Intent data) {
        ImportResult total = new ImportResult();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                total.add(importImage(group, clipData.getItemAt(i).getUri()));
                updateImportProgress(i + 1, clipData.getItemCount());
            }
        } else if (data.getData() != null) {
            total.add(importImage(group, data.getData()));
            updateImportProgress(1, 1);
        }
        return total;
    }

    private ImportResult importFolder(Group group, Uri treeUri, int flags) {
        ImportResult total = new ImportResult();
        if (treeUri == null) {
            total.failed = 1;
            return total;
        }

        int permissionFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, permissionFlags);
        } catch (SecurityException ignored) {
            // Temporary access is still enough for a one-shot import.
        }

        try {
            updateImportMessage("正在扫描文件夹…");
            List<DocumentImage> images = listImagesInTree(treeUri);
            for (int i = 0; i < images.size(); i++) {
                DocumentImage image = images.get(i);
                total.add(importImage(group, image.uri));
                updateImportProgress(i + 1, images.size());
            }
        } catch (SecurityException ignored) {
            total.failed += 1;
        }
        return total;
    }

    private List<DocumentImage> listImagesInTree(Uri treeUri) {
        List<DocumentImage> images = new ArrayList<>();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        collectImages(treeUri, rootId, images);
        images.sort(Comparator.comparing(image -> image.name.toLowerCase(Locale.ROOT)));
        return images;
    }

    private void collectImages(Uri treeUri, String documentId, List<DocumentImage> images) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] columns = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = getContentResolver().query(childrenUri, columns, null, null, null)) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            if (idColumn < 0) {
                return;
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(idColumn);
                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "";
                String mimeType = mimeColumn >= 0 ? cursor.getString(mimeColumn) : "";
                if (id == null) {
                    continue;
                }
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    collectImages(treeUri, id, images);
                } else if (isImageDocument(name, mimeType)) {
                    images.add(new DocumentImage(documentUri, name == null ? "" : name));
                }
            }
        }
    }

    private boolean isImageDocument(String name, String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return true;
        }
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp")
                || lower.endsWith(".avif");
    }

    private ImportResult importImage(Group group, Uri uri) {
        ImportResult result = new ImportResult();
        if (uri == null) {
            result.failed = 1;
            return result;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers grant temporary access only; copying immediately is still enough for private import.
        }

        try {
            String privateUri = copyToPrivateStorage(group.id, uri);
            if (group.images.contains(privateUri)) {
                return result;
            }
            group.images.add(privateUri);
            result.added = 1;
            saveGroups();
            if (deleteOriginal(uri)) {
                result.deleted = 1;
            } else {
                pendingDeleteUris.add(uri);
            }
        } catch (IOException | SecurityException ignored) {
            result.failed = 1;
        }
        return result;
    }

    private String copyToPrivateStorage(String groupId, Uri source) throws IOException {
        File groupDir = new File(new File(getFilesDir(), PRIVATE_IMAGE_DIR), safeStorageName(groupId));
        if (!groupDir.exists() && !groupDir.mkdirs()) {
            throw new IOException("Could not create private image directory.");
        }

        File target = uniquePrivateFile(groupDir, extensionFor(source));
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IOException("Could not open image.");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return Uri.fromFile(target).toString();
    }

    private File uniquePrivateFile(File dir, String ext) {
        String name = System.currentTimeMillis() + "-" + Math.abs((int) System.nanoTime()) + ext;
        File file = new File(dir, name);
        int counter = 2;
        while (file.exists()) {
            file = new File(dir, name.replace(ext, "-" + counter + ext));
            counter++;
        }
        return file;
    }

    private String extensionFor(Uri uri) {
        String type = getContentResolver().getType(uri);
        if ("image/png".equals(type)) {
            return ".png";
        }
        if ("image/webp".equals(type)) {
            return ".webp";
        }
        if ("image/gif".equals(type)) {
            return ".gif";
        }
        if ("image/avif".equals(type)) {
            return ".avif";
        }
        String path = uri.getLastPathSegment();
        if (path != null) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String ext : new String[]{".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif"}) {
                if (lower.endsWith(ext)) {
                    return ".jpeg".equals(ext) ? ".jpg" : ext;
                }
            }
        }
        return ".jpg";
    }

    private boolean deleteOriginal(Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                return DocumentsContract.deleteDocument(getContentResolver(), uri);
            }
        } catch (Exception ignored) {
            // Fall through to ContentResolver delete or system delete request.
        }
        try {
            return getContentResolver().delete(uri, null, null) > 0;
        } catch (SecurityException ignored) {
            return false;
        } catch (IllegalArgumentException ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void requestSystemDeleteIfNeeded() {
        if (pendingDeleteUris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!pendingDeleteUris.isEmpty()) {
                Toast.makeText(this, "部分原图已移入私密空间，但系统未允许自动删除，请在相册里手动删除原图。", Toast.LENGTH_LONG).show();
                pendingDeleteUris.clear();
            }
            return;
        }

        try {
            PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), new ArrayList<>(pendingDeleteUris));
            startIntentSenderForResult(
                    request.getIntentSender(),
                    REQ_CONFIRM_DELETE,
                    null,
                    0,
                    0,
                    0
            );
        } catch (IntentSender.SendIntentException | RuntimeException ignored) {
            Toast.makeText(this, "图片已移入私密空间；部分原图需要在相册里手动删除。", Toast.LENGTH_LONG).show();
            pendingDeleteUris.clear();
        }
    }

    private void promptNewGroup() {
        EditText input = new EditText(this);
        input.setHint("分组名");
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("新建分组")
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "新分组";
                    }
                    activeGroups().add(new Group(String.valueOf(System.currentTimeMillis()), name));
                    saveGroups();
                    showHome();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteGroup(int index) {
        Group group = activeGroups().get(index);
        new AlertDialog.Builder(this)
                .setTitle("删除分组")
                .setMessage("删除「" + group.name + "」？会删除 App 私密空间里的这些图片，不会再影响相册。")
                .setPositiveButton("删除", (dialog, which) -> {
                    deletePrivateFiles(group);
                    activeGroups().remove(index);
                    saveGroups();
                    showHome();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveImage(int groupIndex, int imageIndex) {
        new AlertDialog.Builder(this)
                .setTitle("移出图片")
                .setMessage("从私密空间删除这张图片？")
                .setPositiveButton("移出", (dialog, which) -> {
                    Group group = activeGroups().get(groupIndex);
                    String value = group.images.remove(imageIndex);
                    if (value.equals(group.coverUri)) {
                        group.coverUri = "";
                    }
                    deletePrivateFile(value);
                    saveGroups();
                    showGroup(groupIndex);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private LinearLayout verticalRoot(ThemeSpec theme) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.backgroundColor);
        root.setPadding(dp(10), dp(16), dp(10), dp(16));
        return root;
    }

    private ScrollView wrapScroll(View child) {
        return wrapScroll(child, theme());
    }

    private ScrollView wrapScroll(View child, ThemeSpec theme) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.TRANSPARENT);
        scrollView.addView(child, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    private TextView title(String text) {
        ThemeSpec theme = theme();
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(theme.textColor);
        view.setTextSize(26);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(10), dp(8), dp(10), dp(2));
        return view;
    }

    private TextView smallText(String text) {
        ThemeSpec theme = theme();
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(theme.mutedColor);
        view.setTextSize(15);
        view.setPadding(dp(10), dp(4), dp(10), dp(12));
        return view;
    }

    private Button button(String text) {
        ThemeSpec theme = theme();
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setSingleLine(true);
        view.setTextSize(15);
        view.setTextColor(theme.buttonTextColor);
        view.setBackground(rounded(theme.buttonColor, dp(7), theme.borderColor, dp(1)));
        view.setMinHeight(dp(48));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private ThemeSpec theme() {
        if (THEME_BLUE.equals(themeMode)) {
            return new ThemeSpec(
                    Color.rgb(8, 18, 36),
                    Color.rgb(228, 244, 255),
                    Color.rgb(125, 194, 255),
                    Color.argb(230, 13, 29, 58),
                    Color.rgb(45, 105, 190),
                    Color.rgb(13, 28, 54),
                    Color.rgb(34, 144, 255),
                    Color.WHITE,
                    Color.rgb(180, 230, 255),
                    Color.rgb(17, 44, 91),
                    Color.argb(168, 2, 8, 22)
            );
        }
        if (THEME_RED.equals(themeMode)) {
            return new ThemeSpec(
                    Color.rgb(10, 10, 12),
                    Color.rgb(255, 255, 255),
                    Color.rgb(218, 218, 218),
                    Color.argb(232, 24, 24, 28),
                    Color.rgb(96, 24, 28),
                    Color.rgb(33, 33, 36),
                    Color.rgb(229, 24, 38),
                    Color.WHITE,
                    Color.WHITE,
                    Color.rgb(46, 46, 50),
                    Color.argb(172, 0, 0, 0)
            );
        }
        return new ThemeSpec(
                Color.rgb(247, 247, 245),
                Color.rgb(17, 17, 17),
                Color.rgb(98, 98, 98),
                Color.argb(238, 255, 255, 255),
                Color.rgb(230, 230, 226),
                Color.rgb(245, 245, 242),
                Color.rgb(17, 17, 17),
                Color.WHITE,
                Color.WHITE,
                Color.rgb(210, 210, 206),
                Color.argb(172, 247, 247, 245)
        );
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private String safeStorageName(String value) {
        return value == null ? "default" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void deletePrivateFiles(Group group) {
        for (String image : group.images) {
            deletePrivateFile(image);
        }
    }

    private void deletePrivateFile(String image) {
        try {
            Uri uri = Uri.parse(image);
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                File privateRoot = getFilesDir().getCanonicalFile();
                File target = file.getCanonicalFile();
                String rootPath = privateRoot.getPath();
                String targetPath = target.getPath();
                if (targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator)) {
                    target.delete();
                }
            }
        } catch (IOException | SecurityException ignored) {
            // If the file is already gone or is an older external URI, there is nothing else to clean up.
        }
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        themeMode = prefs.getString(KEY_THEME, THEME_MINIMAL);
        if (!THEME_MINIMAL.equals(themeMode) && !THEME_BLUE.equals(themeMode) && !THEME_RED.equals(themeMode)) {
            themeMode = THEME_MINIMAL;
        }
        homeBackgroundUri = prefs.getString(KEY_HOME_BACKGROUND, "");
        homeBackgroundSourceUri = prefs.getString(KEY_HOME_BACKGROUND_SOURCE, "");
        homeBackgroundBlurLevel = prefs.getInt(KEY_HOME_BACKGROUND_BLUR, DEFAULT_HOME_BLUR_LEVEL);
        if (homeBackgroundBlurLevel < 0) {
            homeBackgroundBlurLevel = 0;
        } else if (homeBackgroundBlurLevel > MAX_HOME_BLUR_LEVEL) {
            homeBackgroundBlurLevel = MAX_HOME_BLUR_LEVEL;
        }
        currentCategoryIndex = prefs.getInt(KEY_CURRENT_CATEGORY, 0);
    }

    private void saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, themeMode)
                .putString(KEY_HOME_BACKGROUND, homeBackgroundUri == null ? "" : homeBackgroundUri)
                .putString(KEY_HOME_BACKGROUND_SOURCE, homeBackgroundSourceUri == null ? "" : homeBackgroundSourceUri)
                .putInt(KEY_HOME_BACKGROUND_BLUR, homeBackgroundBlurLevel)
                .putInt(KEY_CURRENT_CATEGORY, currentCategoryIndex)
                .commit();
    }

    private void loadGroups() {
        categories.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String rawCategories = prefs.getString(KEY_CATEGORIES, "");
        if (rawCategories != null && !rawCategories.isEmpty()) {
            try {
                JSONArray array = new JSONArray(rawCategories);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.getJSONObject(i);
                    Category category = new Category(
                            object.optString("id", String.valueOf(System.currentTimeMillis() + i)),
                            object.optString("name", "默认分类")
                    );
                    category.passwordHash = object.optString("passwordHash", "");
                    category.unlocked = !category.hasPassword();
                    JSONArray groupsArray = object.optJSONArray("groups");
                    if (groupsArray != null) {
                        for (int j = 0; j < groupsArray.length(); j++) {
                            category.groups.add(groupFromJson(groupsArray.getJSONObject(j)));
                        }
                    }
                    categories.add(category);
                }
            } catch (JSONException ignored) {
                categories.clear();
            }
        }

        if (categories.isEmpty()) {
            Category migrated = new Category("default", "默认分类");
            String rawGroups = prefs.getString(KEY_GROUPS, "[]");
            try {
                JSONArray array = new JSONArray(rawGroups);
                for (int i = 0; i < array.length(); i++) {
                    migrated.groups.add(groupFromJson(array.getJSONObject(i)));
                }
            } catch (JSONException ignored) {
                migrated.groups.clear();
            }
            categories.add(migrated);
            currentCategoryIndex = 0;
            saveGroups();
            saveSettings();
        }

        if (currentCategoryIndex < 0 || currentCategoryIndex >= categories.size()) {
            currentCategoryIndex = 0;
        }
    }

    private void saveGroups() {
        JSONArray array = new JSONArray();
        try {
            for (Category category : categories) {
                JSONObject object = new JSONObject();
                object.put("id", category.id);
                object.put("name", category.name);
                object.put("passwordHash", category.passwordHash == null ? "" : category.passwordHash);
                JSONArray groupsArray = new JSONArray();
                for (Group group : category.groups) {
                    groupsArray.put(groupToJson(group));
                }
                object.put("groups", groupsArray);
                array.put(object);
            }
        } catch (JSONException ignored) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_CATEGORIES, array.toString())
                .commit();
    }

    private Group groupFromJson(JSONObject object) throws JSONException {
        Group group = new Group(object.optString("id"), object.optString("name", "未命名"));
        group.coverUri = object.optString("coverUri", "");
        JSONArray images = object.optJSONArray("images");
        if (images != null) {
            for (int j = 0; j < images.length(); j++) {
                group.images.add(images.getString(j));
            }
        }
        return group;
    }

    private JSONObject groupToJson(Group group) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", group.id);
        object.put("name", group.name);
        object.put("coverUri", group.coverUri == null ? "" : group.coverUri);
        JSONArray images = new JSONArray();
        for (String image : group.images) {
            images.put(image);
        }
        object.put("images", images);
        return object;
    }

    private void recoverPrivateFiles() {
        File root = new File(getFilesDir(), PRIVATE_IMAGE_DIR);
        File[] groupDirs = root.listFiles();
        if (groupDirs == null || groupDirs.length == 0) {
            return;
        }

        boolean changed = false;
        for (Category category : categories) {
            for (Group group : category.groups) {
                File dir = new File(root, safeStorageName(group.id));
                File[] files = dir.listFiles();
                if (files == null || files.length == 0) {
                    continue;
                }
                List<File> images = new ArrayList<>();
                for (File file : files) {
                    if (file.isFile() && isImageDocument(file.getName(), null)) {
                        images.add(file);
                    }
                }
                images.sort(Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
                for (File file : images) {
                    String value = Uri.fromFile(file).toString();
                    if (!group.images.contains(value)) {
                        group.images.add(value);
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            saveGroups();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerWindow != null && drawerWindow.isShowing()) {
            dismissNavigationDrawer();
        } else if (currentImageIndex >= 0 && currentGroupIndex >= 0) {
            currentImageIndex = -1;
            showGroup(currentGroupIndex);
        } else if (currentGroupIndex >= 0) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (currentGroupIndex >= 0 && currentImageIndex >= 0) {
            if (handleViewerTouch(event)) {
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private static class Category {
        final String id;
        String name;
        String passwordHash = "";
        boolean unlocked = true;
        final List<Group> groups = new ArrayList<>();

        Category(String id, String name) {
            this.id = id == null || id.isEmpty() ? "default" : id;
            this.name = name == null || name.isEmpty() ? "默认分类" : name;
        }

        boolean hasPassword() {
            return passwordHash != null && !passwordHash.isEmpty();
        }

        boolean isLocked() {
            return hasPassword() && !unlocked;
        }
    }

    private static class Group {
        final String id;
        final String name;
        String coverUri = "";
        final List<String> images = new ArrayList<>();

        Group(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static class DocumentImage {
        final Uri uri;
        final String name;

        DocumentImage(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static class ThemeSpec {
        final int backgroundColor;
        final int textColor;
        final int mutedColor;
        final int cardColor;
        final int buttonColor;
        final int defaultCoverColor;
        final int accentColor;
        final int buttonTextColor;
        final int activeThemeTextColor;
        final int borderColor;
        final int homeOverlayColor;

        ThemeSpec(
                int backgroundColor,
                int textColor,
                int mutedColor,
                int cardColor,
                int buttonColor,
                int defaultCoverColor,
                int accentColor,
                int buttonTextColor,
                int activeThemeTextColor,
                int borderColor,
                int homeOverlayColor
        ) {
            this.backgroundColor = backgroundColor;
            this.textColor = textColor;
            this.mutedColor = mutedColor;
            this.cardColor = cardColor;
            this.buttonColor = buttonColor;
            this.defaultCoverColor = defaultCoverColor;
            this.accentColor = accentColor;
            this.buttonTextColor = buttonTextColor;
            this.activeThemeTextColor = activeThemeTextColor;
            this.borderColor = borderColor;
            this.homeOverlayColor = homeOverlayColor;
        }
    }

    private static class ImportResult {
        int added;
        int deleted;
        int failed;

        void add(ImportResult result) {
            added += result.added;
            deleted += result.deleted;
            failed += result.failed;
        }
    }
}
