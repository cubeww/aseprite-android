package org.aseprite.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
  private static final String TAG = "AsepriteAndroid";
  private static final int TOUCH_DOWN = 0;
  private static final int TOUCH_MOVE = 1;
  private static final int TOUCH_UP = 2;
  private static final int TOUCH_CANCEL = 3;
  private static final int BUTTON_NONE = 0;
  private static final int BUTTON_LEFT = 1;
  private static final int BUTTON_RIGHT = 2;
  private static final int BUTTON_MIDDLE = 3;
  private static final int BUTTON_X1 = 4;
  private static final int BUTTON_X2 = 5;
  private static final int POINTER_UNKNOWN = 0;
  private static final int POINTER_MOUSE = 1;
  private static final int POINTER_TOUCHPAD = 2;
  private static final int POINTER_TOUCH = 3;
  private static final int POINTER_PEN = 4;
  private static final int POINTER_ERASER = 6;
  private static final float PINCH_MIN_DISTANCE = 32.0f;
  private static final float PINCH_MIN_MAGNIFICATION = 0.0025f;
  private static final float PINCH_MIN_PAN_DELTA = 3.0f;
  private static final int THREE_FINGER_TAP_COUNT = 3;
  private static final int REQUEST_OPEN_DOCUMENT = 1001;
  private static final int REQUEST_CREATE_DOCUMENT = 1002;
  private static final String SAVE_TARGET_PREFS = "save-targets";

  private FrameLayout rootView;
  private SurfaceView surfaceView;
  private FrameLayout.LayoutParams surfaceLayoutParams;
  private File documentsDir;
  private String initError;
  private boolean guiStartRequested;
  private int pendingOpenDocumentRequestId = -1;
  private int pendingCreateDocumentRequestId = -1;
  private String pendingCreateDocumentName = "";
  private String pendingCreateDocumentDefaultExtension = "";
  private final Map<String, Uri> saveDocumentUris = new HashMap<>();
  private int touchSlop;
  private int doubleTapSlop;
  private int activePointerId = -1;
  private boolean activeTouchDownSent;
  private boolean pendingTouchDown;
  private int pendingTouchPointerId = -1;
  private float pendingTouchStartX;
  private float pendingTouchStartY;
  private float pendingTouchStartPressure;
  private int pendingTouchButton = BUTTON_LEFT;
  private int pendingTouchPointerType = POINTER_TOUCH;
  private boolean longPressConsumed;
  private long lastTapTime;
  private float lastTapX;
  private float lastTapY;
  private int lastTapPointerType = POINTER_UNKNOWN;
  private boolean pinching;
  private boolean suppressTouchUntilAllUp;
  private int pinchPointerId1 = -1;
  private int pinchPointerId2 = -1;
  private float lastPinchDistance;
  private float lastPinchCenterX;
  private float lastPinchCenterY;
  private boolean threeFingerTapGesture;
  private boolean threeFingerTapMoved;
  private long threeFingerTapStartTime;
  private float threeFingerTapCenterX;
  private float threeFingerTapCenterY;
  private final int[] threeFingerTapPointerIds = new int[THREE_FINGER_TAP_COUNT];
  private final float[] threeFingerTapStartX = new float[THREE_FINGER_TAP_COUNT];
  private final float[] threeFingerTapStartY = new float[THREE_FINGER_TAP_COUNT];
  private boolean pendingThreeFingerUndo;
  private long pendingThreeFingerTapTime;
  private float pendingThreeFingerTapX;
  private float pendingThreeFingerTapY;
  private final Runnable longPressRunnable = this::handleLongPress;
  private final Runnable threeFingerUndoRunnable = this::runPendingThreeFingerUndo;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().setFlags(
        WindowManager.LayoutParams.FLAG_FULLSCREEN,
        WindowManager.LayoutParams.FLAG_FULLSCREEN);
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING |
        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
    configureDisplayCutoutMode();
    enterImmersiveMode();
    ViewConfiguration viewConfiguration = ViewConfiguration.get(this);
    touchSlop = viewConfiguration.getScaledTouchSlop();
    doubleTapSlop = viewConfiguration.getScaledDoubleTapSlop();

    try {
      prepareNativeRuntime();
    }
    catch (Throwable e) {
      initError = e.getClass().getSimpleName() + ": " + e.getMessage();
      Log.e(TAG, "Native runtime init failed", e);
    }

    rootView = new FrameLayout(this);
    rootView.setBackgroundColor(Color.BLACK);

    surfaceView = new AsepriteSurfaceView(this);
    surfaceView.setFocusable(true);
    surfaceView.setFocusableInTouchMode(true);
    surfaceView.setOnTouchListener((view, event) -> {
      view.requestFocus();
      handleTouchEvent(event);
      return true;
    });
    surfaceView.setOnGenericMotionListener((view, event) -> {
      view.requestFocus();
      return handleGenericMotionEvent(event);
    });
    surfaceView.setOnKeyListener((view, keyCode, event) -> handleKeyEvent(event));
    AsepriteBridge.nativeAttachInputView(surfaceView);
    surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(SurfaceHolder holder) {
        AsepriteBridge.nativeSetSurface(holder.getSurface());
      }

      @Override
      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        AsepriteBridge.nativeSetScreenSize(width, height);
        AsepriteBridge.nativeSetSurface(holder.getSurface());
        startGuiIfNeeded();
      }

      @Override
      public void surfaceDestroyed(SurfaceHolder holder) {
        AsepriteBridge.nativeSetSurface(null);
      }
    });

    surfaceLayoutParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT);
    surfaceLayoutParams.topMargin = initialTopSafeInset();

    rootView.addView(surfaceView, surfaceLayoutParams);
    rootView.setOnApplyWindowInsetsListener((view, insets) -> {
      applySurfaceInsets(insets);
      return insets;
    });

    setContentView(rootView, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT));
    rootView.requestApplyInsets();
  }

  @Override
  protected void onResume() {
    super.onResume();
    enterImmersiveMode();
  }

  @Override
  protected void onDestroy() {
    cancelLongPress();
    cancelPendingThreeFingerUndo();
    AsepriteBridge.nativeDetachInputView();
    super.onDestroy();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQUEST_OPEN_DOCUMENT) {
      int requestId = pendingOpenDocumentRequestId;
      pendingOpenDocumentRequestId = -1;

      ArrayList<String> paths = new ArrayList<>();
      if (requestId >= 0 && resultCode == RESULT_OK && data != null) {
        collectOpenDocumentResult(data, paths);
      }

      if (requestId >= 0) {
        AsepriteBridge.nativeOnOpenDocumentResult(
            requestId,
            paths.toArray(new String[paths.size()]));
      }
      return;
    }

    if (requestCode == REQUEST_CREATE_DOCUMENT) {
      int requestId = pendingCreateDocumentRequestId;
      String fallbackName = pendingCreateDocumentName;
      String defaultExtension = pendingCreateDocumentDefaultExtension;
      pendingCreateDocumentRequestId = -1;
      pendingCreateDocumentName = "";
      pendingCreateDocumentDefaultExtension = "";

      String path = "";
      if (requestId >= 0 && resultCode == RESULT_OK && data != null) {
        Uri uri = data.getData();
        if (uri != null) {
          persistWritePermission(uri, data);
          try {
            path = prepareCreateDocumentTarget(uri, fallbackName, defaultExtension);
          }
          catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot prepare save target " + uri, e);
          }
        }
      }

      if (requestId >= 0) {
        AsepriteBridge.nativeOnSaveDocumentResult(requestId, path);
      }
      return;
    }

    super.onActivityResult(requestCode, resultCode, data);
  }

  private void prepareNativeRuntime() throws IOException {
    File filesDir = getFilesDir();
    File cacheDir = getCacheDir();
    File configDir = new File(filesDir, "config");
    File userDir = new File(configDir, "aseprite");
    documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
    if (documentsDir == null) {
      documentsDir = new File(filesDir, "documents");
    }
    File dataDir = new File(userDir, "data");

    requireDirectory(configDir);
    requireDirectory(userDir);
    requireDirectory(documentsDir);
    requireDirectory(cacheDir);
    copyAssetTree("data", dataDir);

    DisplayMetrics metrics = getResources().getDisplayMetrics();
    AsepriteBridge.nativeInitPaths(
        filesDir.getAbsolutePath(),
        cacheDir.getAbsolutePath(),
        documentsDir.getAbsolutePath(),
        metrics.density);
  }

  private void copyAssetTree(String assetPath, File target) throws IOException {
    AssetManager assets = getAssets();
    String[] children = assets.list(assetPath);
    if (children != null && children.length > 0) {
      requireDirectory(target);
      for (String child : children) {
        copyAssetTree(assetPath + "/" + child, new File(target, child));
      }
    }
    else {
      copyAssetFile(assetPath, target);
    }
  }

  private void copyAssetFile(String assetPath, File target) throws IOException {
    File parent = target.getParentFile();
    if (parent != null) {
      requireDirectory(parent);
    }

    try (InputStream in = getAssets().open(assetPath);
         FileOutputStream out = new FileOutputStream(target, false)) {
      byte[] buffer = new byte[16 * 1024];
      int n;
      while ((n = in.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }
    }
  }

  private void requireDirectory(File dir) throws IOException {
    if (dir.isDirectory()) {
      return;
    }
    if (!dir.mkdirs() && !dir.isDirectory()) {
      throw new IOException("Cannot create " + dir.getAbsolutePath());
    }
  }

  private void showOpenDocumentPickerFromNative(int requestId, boolean allowMultiple) {
    runOnUiThread(() -> {
      if (pendingOpenDocumentRequestId != -1) {
        AsepriteBridge.nativeOnOpenDocumentResult(requestId, new String[0]);
        return;
      }

      pendingOpenDocumentRequestId = requestId;
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.setType("*/*");
      intent.putExtra(Intent.EXTRA_MIME_TYPES, openDocumentMimeTypes());
      intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

      try {
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT);
      }
      catch (ActivityNotFoundException e) {
        pendingOpenDocumentRequestId = -1;
        Log.w(TAG, "No document picker available", e);
        AsepriteBridge.nativeOnOpenDocumentResult(requestId, new String[0]);
      }
    });
  }

  private void showCreateDocumentPickerFromNative(
      int requestId, String initialName, String defaultExtension) {
    runOnUiThread(() -> {
      if (pendingCreateDocumentRequestId != -1) {
        AsepriteBridge.nativeOnSaveDocumentResult(requestId, "");
        return;
      }

      String safeDefaultExtension = normalizeExtension(defaultExtension);
      String title = saveNameWithExtension(initialName, safeDefaultExtension);

      pendingCreateDocumentRequestId = requestId;
      pendingCreateDocumentName = title;
      pendingCreateDocumentDefaultExtension = safeDefaultExtension;

      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.setType(mimeTypeForName(title));
      intent.putExtra(Intent.EXTRA_TITLE, title);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

      try {
        startActivityForResult(intent, REQUEST_CREATE_DOCUMENT);
      }
      catch (ActivityNotFoundException e) {
        pendingCreateDocumentRequestId = -1;
        pendingCreateDocumentName = "";
        pendingCreateDocumentDefaultExtension = "";
        Log.w(TAG, "No create document picker available", e);
        AsepriteBridge.nativeOnSaveDocumentResult(requestId, "");
      }
    });
  }

  private void collectOpenDocumentResult(Intent data, List<String> paths) {
    ClipData clipData = data.getClipData();
    if (clipData != null) {
      for (int i = 0; i < clipData.getItemCount(); ++i) {
        copyOpenDocumentUri(clipData.getItemAt(i).getUri(), paths);
      }
    }
    else {
      copyOpenDocumentUri(data.getData(), paths);
    }
  }

  private void copyOpenDocumentUri(Uri uri, List<String> paths) {
    if (uri == null) {
      return;
    }

    try {
      File importDir = new File(documentsDir, "Imported");
      requireDirectory(importDir);
      File target = uniqueImportFile(importDir, displayNameForUri(uri));

      try (InputStream in = getContentResolver().openInputStream(uri);
           FileOutputStream out = new FileOutputStream(target, false)) {
        if (in == null) {
          throw new IOException("Cannot open " + uri);
        }

        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
          out.write(buffer, 0, n);
        }
      }

      target = normalizeImportedFileName(target);
      paths.add(target.getAbsolutePath());
    }
    catch (IOException e) {
      Log.w(TAG, "Cannot import " + uri, e);
    }
  }

  private String[] openDocumentMimeTypes() {
    return new String[] {
        "application/x-aseprite",
        "application/octet-stream",
        "image/*",
        "text/*"
    };
  }

  private String displayNameForUri(Uri uri) {
    return displayNameForUri(uri, "imported");
  }

  private String displayNameForUri(Uri uri, String fallbackName) {
    try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (nameIndex >= 0) {
          String name = cursor.getString(nameIndex);
          if (name != null && !name.trim().isEmpty()) {
            return sanitizeFileName(name);
          }
        }
      }
    }
    catch (RuntimeException e) {
      Log.w(TAG, "Cannot query display name for " + uri, e);
    }

    String fallback = uri.getLastPathSegment();
    if (fallback == null || fallback.trim().isEmpty()) {
      fallback = fallbackName;
    }
    return sanitizeFileName(fallback);
  }

  private String prepareCreateDocumentTarget(
      Uri uri, String fallbackName, String defaultExtension) throws IOException {
    File saveDir = new File(documentsDir, ".SaveTargets");
    requireDirectory(saveDir);

    String name = ensureFileExtension(displayNameForUri(uri, fallbackName), defaultExtension);
    File target = uniqueImportFile(saveDir, name);
    synchronized (saveDocumentUris) {
      saveDocumentUris.put(target.getAbsolutePath(), uri);
    }
    savePersistedDocumentUri(target.getAbsolutePath(), uri);
    return target.getAbsolutePath();
  }

  private boolean finishCreateDocumentFromNative(
      String localPath, boolean success, boolean keepMapping) {
    if (localPath == null || localPath.isEmpty()) {
      return true;
    }

    Uri uri = lookupSaveDocumentUri(localPath);
    if (uri == null) {
      return true;
    }

    if (!success) {
      if (!keepMapping) {
        removeSaveDocumentUri(localPath);
      }
      return true;
    }

    try {
      copyLocalFileToUri(new File(localPath), uri);
      if (!keepMapping) {
        removeSaveDocumentUri(localPath);
      }
      return true;
    }
    catch (IOException | RuntimeException e) {
      Log.w(TAG, "Cannot write saved document to " + uri, e);
      if (!keepMapping) {
        removeSaveDocumentUri(localPath);
      }
      return false;
    }
  }

  private Uri lookupSaveDocumentUri(String localPath) {
    synchronized (saveDocumentUris) {
      Uri uri = saveDocumentUris.get(localPath);
      if (uri != null) {
        return uri;
      }
    }

    String value = getSharedPreferences(SAVE_TARGET_PREFS, MODE_PRIVATE).getString(localPath, null);
    if (value == null || value.isEmpty()) {
      return null;
    }

    Uri uri = Uri.parse(value);
    synchronized (saveDocumentUris) {
      saveDocumentUris.put(localPath, uri);
    }
    return uri;
  }

  private void savePersistedDocumentUri(String localPath, Uri uri) {
    getSharedPreferences(SAVE_TARGET_PREFS, MODE_PRIVATE)
        .edit()
        .putString(localPath, uri.toString())
        .apply();
  }

  private void removeSaveDocumentUri(String localPath) {
    synchronized (saveDocumentUris) {
      saveDocumentUris.remove(localPath);
    }
    SharedPreferences.Editor editor =
        getSharedPreferences(SAVE_TARGET_PREFS, MODE_PRIVATE).edit();
    editor.remove(localPath);
    editor.apply();
  }

  private void copyLocalFileToUri(File source, Uri uri) throws IOException {
    if (!source.isFile()) {
      throw new IOException("Saved file does not exist: " + source.getAbsolutePath());
    }

    try (InputStream in = new FileInputStream(source);
         OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
      if (out == null) {
        throw new IOException("Cannot open " + uri);
      }

      byte[] buffer = new byte[64 * 1024];
      int n;
      while ((n = in.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }
      out.flush();
    }
  }

  private void persistWritePermission(Uri uri, Intent data) {
    int flags = data.getFlags() &
        (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    if (flags == 0) {
      return;
    }

    try {
      getContentResolver().takePersistableUriPermission(uri, flags);
    }
    catch (SecurityException e) {
      Log.d(TAG, "Persistable URI permission unavailable for " + uri);
    }
  }

  private String saveNameWithExtension(String name, String defaultExtension) {
    String fallback = defaultExtension.isEmpty() ? "Untitled" : "Untitled." + defaultExtension;
    String sanitized = sanitizeFileName(name == null || name.trim().isEmpty() ? fallback : name);
    return ensureFileExtension(sanitized, defaultExtension);
  }

  private String ensureFileExtension(String name, String defaultExtension) {
    String extension = normalizeExtension(defaultExtension);
    if (extension.isEmpty() || hasFileExtension(name)) {
      return name;
    }
    return name + "." + extension;
  }

  private boolean hasFileExtension(String name) {
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    int dot = name.lastIndexOf('.');
    return dot > slash && dot < name.length() - 1;
  }

  private String normalizeExtension(String extension) {
    if (extension == null) {
      return "";
    }

    String result = extension.trim();
    while (result.startsWith(".")) {
      result = result.substring(1);
    }
    if (result.isEmpty()) {
      return "";
    }
    return sanitizeFileName(result).toLowerCase(Locale.US);
  }

  private String mimeTypeForName(String name) {
    String lower = name.toLowerCase(Locale.US);
    if (lower.endsWith(".ase") || lower.endsWith(".aseprite")) return "application/x-aseprite";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".gif")) return "image/gif";
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
    if (lower.endsWith(".webp")) return "image/webp";
    if (lower.endsWith(".bmp")) return "image/bmp";
    if (lower.endsWith(".svg")) return "image/svg+xml";
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".txt")) return "text/plain";
    return "application/octet-stream";
  }

  private File normalizeImportedFileName(File file) {
    String name = file.getName();
    if (hasAsepriteExtension(name) || !looksLikeAsepriteFile(file)) {
      return file;
    }

    File target = uniqueImportFile(file.getParentFile(), stripFileExtension(name) + ".aseprite");
    if (file.renameTo(target)) {
      return target;
    }
    return file;
  }

  private boolean hasAsepriteExtension(String name) {
    String lower = name.toLowerCase(Locale.US);
    return lower.endsWith(".ase") || lower.endsWith(".aseprite");
  }

  private String stripFileExtension(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private boolean looksLikeAsepriteFile(File file) {
    byte[] header = new byte[6];
    try (InputStream in = new FileInputStream(file)) {
      int n = in.read(header);
      return n >= 6 &&
          (header[4] & 0xff) == 0xe0 &&
          (header[5] & 0xff) == 0xa5;
    }
    catch (IOException e) {
      return false;
    }
  }

  private String sanitizeFileName(String name) {
    StringBuilder result = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); ++i) {
      char ch = name.charAt(i);
      if (ch < 32 || ch == '/' || ch == '\\' || ch == ':') {
        result.append('_');
      }
      else {
        result.append(ch);
      }
    }

    String sanitized = result.toString().trim();
    if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) {
      return "imported";
    }
    return sanitized;
  }

  private File uniqueImportFile(File dir, String name) {
    File target = new File(dir, name);
    if (!target.exists()) {
      return target;
    }

    int dot = name.lastIndexOf('.');
    String title = dot > 0 ? name.substring(0, dot) : name;
    String extension = dot > 0 ? name.substring(dot) : "";
    for (int i = 1; ; ++i) {
      target = new File(dir, title + "-" + i + extension);
      if (!target.exists()) {
        return target;
      }
    }
  }

  private void startGuiIfNeeded() {
    if (guiStartRequested || initError != null) {
      return;
    }

    guiStartRequested = true;
    int result = AsepriteBridge.nativeStartGui();
    if (result != 0) {
      Log.w(TAG, "GUI start returned " + result);
    }
  }

  private void handleTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();
    int actionIndex = event.getActionIndex();

    switch (action) {
      case MotionEvent.ACTION_DOWN:
        resetPinchState();
        resetThreeFingerTapState();
        suppressTouchUntilAllUp = false;
        longPressConsumed = false;
        activePointerId = event.getPointerId(0);
        startPointerDown(event, 0);
        break;

      case MotionEvent.ACTION_POINTER_DOWN:
        if (threeFingerTapGesture) {
          threeFingerTapMoved = true;
        }
        else if (event.getPointerCount() == THREE_FINGER_TAP_COUNT &&
            canStartThreeFingerTap(event)) {
          beginThreeFingerTap(event);
        }
        else if (!pinching && event.getPointerCount() >= 2 && canStartPinch(event, actionIndex)) {
          beginPinch(event);
        }
        break;

      case MotionEvent.ACTION_MOVE: {
        if (threeFingerTapGesture) {
          updateThreeFingerTap(event);
        }
        else if (pinching) {
          sendPinch(event);
        }
        else if (!suppressTouchUntilAllUp) {
          int pointerIndex = event.findPointerIndex(activePointerId);
          if (pointerIndex >= 0) {
            if (pendingTouchDown && movedPastTouchSlop(event, pointerIndex)) {
              cancelLongPress();
              commitPendingTouchDown();
            }
            sendPointer(event, pointerIndex, TOUCH_MOVE, pendingOrEventButton(event));
          }
        }
        break;
      }

      case MotionEvent.ACTION_UP:
        if (threeFingerTapGesture) {
          finishThreeFingerTap(event);
        }
        else if (pinching) {
          resetPinchState();
        }
        else if (!longPressConsumed && activePointerId != -1) {
          int pointerIndex = event.findPointerIndex(activePointerId);
          pointerIndex = pointerIndex >= 0 ? pointerIndex : actionIndex;
          if (pendingTouchDown) {
            if (isDoubleTap(event, pointerIndex)) {
              cancelPendingTouchDown();
              sendDoubleClick(event, pointerIndex, pendingTouchButton);
              resetLastTap();
            }
            else {
              cancelPendingTouchDown();
              sendPointer(event, pointerIndex, TOUCH_DOWN, pendingTouchButton);
              sendPointer(event, pointerIndex, TOUCH_UP, pendingTouchButton);
              rememberTap(event, pointerIndex);
            }
          }
          else if (activeTouchDownSent) {
            sendPointer(event, pointerIndex, TOUCH_UP, pendingOrEventButton(event));
          }
        }
        cancelLongPress();
        cancelPendingTouchDown();
        activePointerId = -1;
        activeTouchDownSent = false;
        longPressConsumed = false;
        suppressTouchUntilAllUp = false;
        break;

      case MotionEvent.ACTION_POINTER_UP:
        if (threeFingerTapGesture) {
          finishThreeFingerTap(event);
        }
        else if (pinching) {
          int pointerId = event.getPointerId(actionIndex);
          if (pointerId == pinchPointerId1 || pointerId == pinchPointerId2) {
            resetPinchState();
            suppressTouchUntilAllUp = true;
          }
        }
        else if (!suppressTouchUntilAllUp && event.getPointerId(actionIndex) == activePointerId) {
          cancelLongPress();
          if (pendingTouchDown) {
            cancelPendingTouchDown();
          }
          else if (activeTouchDownSent) {
            sendPointer(event, actionIndex, TOUCH_UP, pendingOrEventButton(event));
          }
          activePointerId = -1;
          activeTouchDownSent = false;
          suppressTouchUntilAllUp = true;
        }
        break;

      case MotionEvent.ACTION_CANCEL:
        if (threeFingerTapGesture) {
          resetThreeFingerTapState();
        }
        else if (pinching) {
          resetPinchState();
        }
        else if (activePointerId != -1) {
          int pointerIndex = event.findPointerIndex(activePointerId);
          if (pendingTouchDown) {
            cancelPendingTouchDown();
          }
          else if (activeTouchDownSent && pointerIndex >= 0) {
            sendPointer(event, pointerIndex, TOUCH_CANCEL, pendingOrEventButton(event));
          }
        }
        cancelLongPress();
        activePointerId = -1;
        activeTouchDownSent = false;
        longPressConsumed = false;
        suppressTouchUntilAllUp = false;
        break;

      case MotionEvent.ACTION_BUTTON_PRESS:
        handleButtonAction(event, actionIndex, TOUCH_DOWN);
        break;

      case MotionEvent.ACTION_BUTTON_RELEASE:
        handleButtonAction(event, actionIndex, TOUCH_UP);
        break;

      default:
        break;
    }
  }

  private void beginThreeFingerTap(MotionEvent event) {
    cancelLongPress();
    cancelActivePointerForGesture(event);
    resetPinchState();
    resetLastTap();

    suppressTouchUntilAllUp = true;
    threeFingerTapGesture = true;
    threeFingerTapMoved = false;
    threeFingerTapStartTime = event.getEventTime();
    threeFingerTapCenterX = averageX(event);
    threeFingerTapCenterY = averageY(event);

    for (int i = 0; i < THREE_FINGER_TAP_COUNT; ++i) {
      threeFingerTapPointerIds[i] = event.getPointerId(i);
      threeFingerTapStartX[i] = event.getX(i);
      threeFingerTapStartY[i] = event.getY(i);
    }
  }

  private void cancelActivePointerForGesture(MotionEvent event) {
    if (activePointerId == -1) {
      return;
    }

    int pointerIndex = event.findPointerIndex(activePointerId);
    if (pendingTouchDown) {
      cancelPendingTouchDown();
    }
    else if (activeTouchDownSent && pointerIndex >= 0) {
      sendPointer(event, pointerIndex, TOUCH_CANCEL, pendingOrEventButton(event));
    }

    activePointerId = -1;
    activeTouchDownSent = false;
  }

  private boolean canStartThreeFingerTap(MotionEvent event) {
    if (event.getPointerCount() != THREE_FINGER_TAP_COUNT) {
      return false;
    }

    for (int i = 0; i < THREE_FINGER_TAP_COUNT; ++i) {
      if (pointerTypeFromEvent(event, i) != POINTER_TOUCH) {
        return false;
      }
    }
    return true;
  }

  private void updateThreeFingerTap(MotionEvent event) {
    if (event.getPointerCount() != THREE_FINGER_TAP_COUNT) {
      threeFingerTapMoved = true;
      return;
    }

    float slopSquared = touchSlop * touchSlop;
    for (int i = 0; i < THREE_FINGER_TAP_COUNT; ++i) {
      int pointerIndex = event.findPointerIndex(threeFingerTapPointerIds[i]);
      if (pointerIndex < 0) {
        threeFingerTapMoved = true;
        return;
      }

      float dx = event.getX(pointerIndex) - threeFingerTapStartX[i];
      float dy = event.getY(pointerIndex) - threeFingerTapStartY[i];
      if (dx * dx + dy * dy > slopSquared) {
        threeFingerTapMoved = true;
        return;
      }
    }
  }

  private void finishThreeFingerTap(MotionEvent event) {
    if (!threeFingerTapMoved &&
        event.getEventTime() - threeFingerTapStartTime <= ViewConfiguration.getLongPressTimeout()) {
      handleThreeFingerTap(event.getEventTime(), threeFingerTapCenterX, threeFingerTapCenterY);
    }

    resetThreeFingerTapState();
    suppressTouchUntilAllUp = true;
  }

  private void handleThreeFingerTap(long eventTime, float x, float y) {
    if (pendingThreeFingerUndo && isPendingThreeFingerDoubleTap(eventTime, x, y)) {
      cancelPendingThreeFingerUndo();
      sendShortcut(KeyEvent.KEYCODE_Y);
      return;
    }

    if (pendingThreeFingerUndo) {
      cancelPendingThreeFingerUndo();
      sendShortcut(KeyEvent.KEYCODE_Z);
    }

    scheduleThreeFingerUndo(eventTime, x, y);
  }

  private void scheduleThreeFingerUndo(long eventTime, float x, float y) {
    pendingThreeFingerUndo = true;
    pendingThreeFingerTapTime = eventTime;
    pendingThreeFingerTapX = x;
    pendingThreeFingerTapY = y;

    if (surfaceView != null) {
      surfaceView.removeCallbacks(threeFingerUndoRunnable);
      surfaceView.postDelayed(threeFingerUndoRunnable, ViewConfiguration.getDoubleTapTimeout());
    }
  }

  private boolean isPendingThreeFingerDoubleTap(long eventTime, float x, float y) {
    long elapsed = eventTime - pendingThreeFingerTapTime;
    if (elapsed < 0 || elapsed > ViewConfiguration.getDoubleTapTimeout()) {
      return false;
    }

    float dx = x - pendingThreeFingerTapX;
    float dy = y - pendingThreeFingerTapY;
    return dx * dx + dy * dy <= doubleTapSlop * doubleTapSlop;
  }

  private void runPendingThreeFingerUndo() {
    if (!pendingThreeFingerUndo) {
      return;
    }

    pendingThreeFingerUndo = false;
    sendShortcut(KeyEvent.KEYCODE_Z);
  }

  private void cancelPendingThreeFingerUndo() {
    pendingThreeFingerUndo = false;
    if (surfaceView != null) {
      surfaceView.removeCallbacks(threeFingerUndoRunnable);
    }
  }

  private void sendShortcut(int keyCode) {
    AsepriteBridge.nativeOnKey(TOUCH_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON);
    AsepriteBridge.nativeOnKey(TOUCH_UP, keyCode, 0, KeyEvent.META_CTRL_ON);
  }

  private float averageX(MotionEvent event) {
    float sum = 0.0f;
    for (int i = 0; i < THREE_FINGER_TAP_COUNT; ++i) {
      sum += event.getX(i);
    }
    return sum / THREE_FINGER_TAP_COUNT;
  }

  private float averageY(MotionEvent event) {
    float sum = 0.0f;
    for (int i = 0; i < THREE_FINGER_TAP_COUNT; ++i) {
      sum += event.getY(i);
    }
    return sum / THREE_FINGER_TAP_COUNT;
  }

  private void resetThreeFingerTapState() {
    threeFingerTapGesture = false;
    threeFingerTapMoved = false;
    threeFingerTapStartTime = 0;
    threeFingerTapCenterX = 0.0f;
    threeFingerTapCenterY = 0.0f;
    for (int i = 0; i < THREE_FINGER_TAP_COUNT; ++i) {
      threeFingerTapPointerIds[i] = -1;
      threeFingerTapStartX[i] = 0.0f;
      threeFingerTapStartY[i] = 0.0f;
    }
  }

  private void beginPinch(MotionEvent event) {
    cancelLongPress();
    cancelActivePointerForGesture(event);
    suppressTouchUntilAllUp = true;
    pinching = true;
    pinchPointerId1 = event.getPointerId(0);
    pinchPointerId2 = event.getPointerId(1);
    lastPinchDistance = pointerDistance(event, 0, 1);
    lastPinchCenterX = centerX(event, 0, 1);
    lastPinchCenterY = centerY(event, 0, 1);
    if (lastPinchDistance < PINCH_MIN_DISTANCE) {
      lastPinchDistance = 0.0f;
    }
  }

  private boolean canStartPinch(MotionEvent event, int actionIndex) {
    return pointerTypeFromEvent(event, 0) == POINTER_TOUCH &&
           pointerTypeFromEvent(event, actionIndex) == POINTER_TOUCH;
  }

  private void sendPinch(MotionEvent event) {
    int index1 = event.findPointerIndex(pinchPointerId1);
    int index2 = event.findPointerIndex(pinchPointerId2);
    if (index1 < 0 || index2 < 0) {
      resetPinchState();
      suppressTouchUntilAllUp = true;
      return;
    }

    float distance = pointerDistance(event, index1, index2);
    if (distance < PINCH_MIN_DISTANCE) {
      return;
    }

    float x = centerX(event, index1, index2);
    float y = centerY(event, index1, index2);
    float dx = x - lastPinchCenterX;
    float dy = y - lastPinchCenterY;
    if (Math.abs(dx) >= PINCH_MIN_PAN_DELTA || Math.abs(dy) >= PINCH_MIN_PAN_DELTA) {
      AsepriteBridge.nativeOnTouchPan(x, y, dx, dy);
      lastPinchCenterX = x;
      lastPinchCenterY = y;
    }

    if (lastPinchDistance < PINCH_MIN_DISTANCE) {
      lastPinchDistance = distance;
      return;
    }

    float magnification = distance / lastPinchDistance - 1.0f;
    if (Math.abs(magnification) < PINCH_MIN_MAGNIFICATION) {
      return;
    }

    AsepriteBridge.nativeOnTouchMagnify(
        x,
        y,
        magnification);
    lastPinchDistance = distance;
  }

  private float pointerDistance(MotionEvent event, int index1, int index2) {
    float dx = event.getX(index1) - event.getX(index2);
    float dy = event.getY(index1) - event.getY(index2);
    return (float)Math.hypot(dx, dy);
  }

  private float centerX(MotionEvent event, int index1, int index2) {
    return (event.getX(index1) + event.getX(index2)) * 0.5f;
  }

  private float centerY(MotionEvent event, int index1, int index2) {
    return (event.getY(index1) + event.getY(index2)) * 0.5f;
  }

  private void resetPinchState() {
    pinching = false;
    pinchPointerId1 = -1;
    pinchPointerId2 = -1;
    lastPinchDistance = 0.0f;
    lastPinchCenterX = 0.0f;
    lastPinchCenterY = 0.0f;
  }

  private void startPointerDown(MotionEvent event, int pointerIndex) {
    int button = buttonFromEvent(event, BUTTON_LEFT);
    int pointerType = pointerTypeFromEvent(event, pointerIndex);
    pendingTouchButton = button;
    pendingTouchPointerType = pointerType;

    if (!shouldDelayPointerDown(pointerType, button)) {
      cancelLongPress();
      activeTouchDownSent = true;
      sendPointer(event, pointerIndex, TOUCH_MOVE, BUTTON_NONE);
      sendPointer(event, pointerIndex, TOUCH_DOWN, button);
      return;
    }

    pendingTouchDown = true;
    pendingTouchPointerId = event.getPointerId(pointerIndex);
    pendingTouchStartX = event.getX(pointerIndex);
    pendingTouchStartY = event.getY(pointerIndex);
    pendingTouchStartPressure = event.getPressure(pointerIndex);
    activeTouchDownSent = false;
    sendPointer(event, pointerIndex, TOUCH_MOVE, BUTTON_NONE);
    scheduleLongPress();
  }

  private void commitPendingTouchDown() {
    if (!pendingTouchDown) {
      return;
    }

    pendingTouchDown = false;
    pendingTouchPointerId = -1;
    activeTouchDownSent = true;
    AsepriteBridge.nativeOnPointer(
        TOUCH_DOWN,
        pendingTouchStartX,
        pendingTouchStartY,
        pendingTouchStartPressure,
        pendingTouchButton,
        pendingTouchPointerType);
  }

  private void cancelPendingTouchDown() {
    pendingTouchDown = false;
    pendingTouchPointerId = -1;
  }

  private boolean movedPastTouchSlop(MotionEvent event, int pointerIndex) {
    if (!pendingTouchDown || event.getPointerId(pointerIndex) != pendingTouchPointerId) {
      return false;
    }

    float dx = event.getX(pointerIndex) - pendingTouchStartX;
    float dy = event.getY(pointerIndex) - pendingTouchStartY;
    return dx * dx + dy * dy >= touchSlop * touchSlop;
  }

  private void sendPointer(MotionEvent event, int pointerIndex, int touchAction, int button) {
    if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
      return;
    }

    AsepriteBridge.nativeOnPointer(
        touchAction,
        event.getX(pointerIndex),
        event.getY(pointerIndex),
        event.getPressure(pointerIndex),
        button,
        pointerTypeFromEvent(event, pointerIndex));
  }

  private void sendDoubleClick(MotionEvent event, int pointerIndex, int button) {
    AsepriteBridge.nativeOnPointerDoubleClick(
        event.getX(pointerIndex),
        event.getY(pointerIndex),
        event.getPressure(pointerIndex),
        button,
        pointerTypeFromEvent(event, pointerIndex));
  }

  private void handleButtonAction(MotionEvent event, int actionIndex, int touchAction) {
    int pointerIndex = actionIndex >= 0 && actionIndex < event.getPointerCount() ? actionIndex : 0;
    int button = buttonFromAction(event);
    if (button == BUTTON_NONE) {
      button = buttonFromEvent(event, BUTTON_LEFT);
    }
    sendPointer(event, pointerIndex, touchAction, button);
  }

  private int pendingOrEventButton(MotionEvent event) {
    return pendingTouchButton != BUTTON_NONE ? pendingTouchButton : buttonFromEvent(event, BUTTON_LEFT);
  }

  private boolean shouldDelayPointerDown(int pointerType, int button) {
    return pointerType == POINTER_TOUCH && button == BUTTON_LEFT;
  }

  private int buttonFromAction(MotionEvent event) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return buttonFromAndroidButton(event.getActionButton(), BUTTON_NONE);
    }
    return BUTTON_NONE;
  }

  private int buttonFromEvent(MotionEvent event, int fallback) {
    return buttonFromAndroidButton(event.getButtonState(), fallback);
  }

  private int buttonFromAndroidButton(int buttonState, int fallback) {
    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0)
      return BUTTON_LEFT;
    if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0)
      return BUTTON_RIGHT;
    if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0)
      return BUTTON_MIDDLE;
    if ((buttonState & MotionEvent.BUTTON_BACK) != 0)
      return BUTTON_X1;
    if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0)
      return BUTTON_X2;
    return fallback;
  }

  private int pointerTypeFromEvent(MotionEvent event, int pointerIndex) {
    if (event.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)) {
      return POINTER_TOUCHPAD;
    }

    switch (event.getToolType(pointerIndex)) {
      case MotionEvent.TOOL_TYPE_FINGER:
        return POINTER_TOUCH;
      case MotionEvent.TOOL_TYPE_MOUSE:
        return POINTER_MOUSE;
      case MotionEvent.TOOL_TYPE_STYLUS:
        return POINTER_PEN;
      case MotionEvent.TOOL_TYPE_ERASER:
        return POINTER_ERASER;
      default:
        return POINTER_UNKNOWN;
    }
  }

  private void scheduleLongPress() {
    cancelLongPress();
    surfaceView.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
  }

  private void cancelLongPress() {
    if (surfaceView != null) {
      surfaceView.removeCallbacks(longPressRunnable);
    }
  }

  private void handleLongPress() {
    if (!pendingTouchDown || pinching || suppressTouchUntilAllUp ||
        pendingTouchPointerType != POINTER_TOUCH) {
      return;
    }

    pendingTouchDown = false;
    pendingTouchPointerId = -1;
    activePointerId = -1;
    activeTouchDownSent = false;
    longPressConsumed = true;
    suppressTouchUntilAllUp = true;
    resetLastTap();

    AsepriteBridge.nativeOnPointer(
        TOUCH_DOWN,
        pendingTouchStartX,
        pendingTouchStartY,
        pendingTouchStartPressure,
        BUTTON_RIGHT,
        POINTER_TOUCH);
    AsepriteBridge.nativeOnPointer(
        TOUCH_UP,
        pendingTouchStartX,
        pendingTouchStartY,
        pendingTouchStartPressure,
        BUTTON_RIGHT,
        POINTER_TOUCH);
  }

  private boolean isDoubleTap(MotionEvent event, int pointerIndex) {
    if (lastTapTime == 0 || pendingTouchPointerType != lastTapPointerType) {
      return false;
    }

    long elapsed = event.getEventTime() - lastTapTime;
    if (elapsed < 0 || elapsed > ViewConfiguration.getDoubleTapTimeout()) {
      return false;
    }

    float dx = event.getX(pointerIndex) - lastTapX;
    float dy = event.getY(pointerIndex) - lastTapY;
    return dx * dx + dy * dy <= doubleTapSlop * doubleTapSlop;
  }

  private void rememberTap(MotionEvent event, int pointerIndex) {
    lastTapTime = event.getEventTime();
    lastTapX = event.getX(pointerIndex);
    lastTapY = event.getY(pointerIndex);
    lastTapPointerType = pointerTypeFromEvent(event, pointerIndex);
  }

  private void resetLastTap() {
    lastTapTime = 0;
    lastTapPointerType = POINTER_UNKNOWN;
  }

  private boolean handleGenericMotionEvent(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_SCROLL) {
      int pointerType = pointerTypeFromEvent(event, 0);
      float hscroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
      float vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
      boolean precise = pointerType == POINTER_TOUCHPAD;
      float multiplier = precise ? getResources().getDisplayMetrics().density * 12.0f : 1.0f;
      AsepriteBridge.nativeOnPointerWheel(
          event.getX(),
          event.getY(),
          -hscroll * multiplier,
          -vscroll * multiplier,
          pointerType,
          precise);
      return true;
    }

    if (action == MotionEvent.ACTION_HOVER_MOVE ||
        action == MotionEvent.ACTION_HOVER_ENTER ||
        action == MotionEvent.ACTION_HOVER_EXIT) {
      sendPointer(event, 0, TOUCH_MOVE, BUTTON_NONE);
      return true;
    }

    return false;
  }

  private boolean handleKeyEvent(KeyEvent event) {
    int action = event.getAction();
    if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
      return false;
    }

    AsepriteBridge.nativeOnKey(
        action == KeyEvent.ACTION_DOWN ? TOUCH_DOWN : TOUCH_UP,
        event.getKeyCode(),
        event.getUnicodeChar(),
        event.getMetaState());
    return true;
  }

  private void enterImmersiveMode() {
    View decor = getWindow().getDecorView();
    decor.setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
        View.SYSTEM_UI_FLAG_FULLSCREEN |
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
  }

  private void configureDisplayCutoutMode() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return;
    }

    WindowManager.LayoutParams params = getWindow().getAttributes();
    params.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    getWindow().setAttributes(params);
  }

  private void applySurfaceInsets(WindowInsets insets) {
    if (surfaceLayoutParams == null) {
      return;
    }

    boolean landscape = isLandscape();
    int left = insets.getSystemWindowInsetLeft();
    int top = landscape ? 0 : Math.max(fallbackStatusBarHeight(), insets.getSystemWindowInsetTop());
    int right = insets.getSystemWindowInsetRight();
    int bottom = insets.getSystemWindowInsetBottom();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      DisplayCutout cutout = insets.getDisplayCutout();
      if (cutout != null) {
        left = Math.max(left, cutout.getSafeInsetLeft());
        top = Math.max(top, cutout.getSafeInsetTop());
        right = Math.max(right, cutout.getSafeInsetRight());
        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
      }
    }

    if (surfaceLayoutParams.leftMargin == left &&
        surfaceLayoutParams.topMargin == top &&
        surfaceLayoutParams.rightMargin == right &&
        surfaceLayoutParams.bottomMargin == bottom) {
      return;
    }

    surfaceLayoutParams.setMargins(left, top, right, bottom);
    surfaceView.setLayoutParams(surfaceLayoutParams);
  }

  private int initialTopSafeInset() {
    return isLandscape() ? 0 : fallbackStatusBarHeight();
  }

  private boolean isLandscape() {
    return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
  }

  private int fallbackStatusBarHeight() {
    int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
    if (resourceId > 0) {
      return getResources().getDimensionPixelSize(resourceId);
    }
    return 0;
  }

  private static final class AsepriteSurfaceView extends SurfaceView {
    AsepriteSurfaceView(Context context) {
      super(context);
    }

    @Override
    public boolean onCheckIsTextEditor() {
      return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
      outAttrs.inputType =
          InputType.TYPE_CLASS_TEXT |
          InputType.TYPE_TEXT_FLAG_MULTI_LINE |
          InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
          InputType.TYPE_TEXT_VARIATION_NORMAL;
      outAttrs.imeOptions =
          EditorInfo.IME_ACTION_NONE |
          EditorInfo.IME_FLAG_NO_EXTRACT_UI |
          EditorInfo.IME_FLAG_NO_FULLSCREEN;
      return new AsepriteInputConnection(this);
    }

    public void showOpenDocumentPickerFromNative(int requestId, boolean allowMultiple) {
      Context context = getContext();
      if (context instanceof MainActivity) {
        ((MainActivity)context).showOpenDocumentPickerFromNative(requestId, allowMultiple);
      }
      else {
        AsepriteBridge.nativeOnOpenDocumentResult(requestId, new String[0]);
      }
    }

    public void showCreateDocumentPickerFromNative(
        int requestId, String initialName, String defaultExtension) {
      Context context = getContext();
      if (context instanceof MainActivity) {
        ((MainActivity)context).showCreateDocumentPickerFromNative(
            requestId, initialName, defaultExtension);
      }
      else {
        AsepriteBridge.nativeOnSaveDocumentResult(requestId, "");
      }
    }

    public boolean finishCreateDocumentFromNative(
        String localPath, boolean success, boolean keepMapping) {
      Context context = getContext();
      if (context instanceof MainActivity) {
        return ((MainActivity)context).finishCreateDocumentFromNative(
            localPath, success, keepMapping);
      }
      return true;
    }

    public void showSoftKeyboardFromNative() {
      post(() -> {
        requestFocusFromTouch();
        requestFocus();
        InputMethodManager imm =
            (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
          imm.restartInput(this);
          boolean shown = imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
          Log.d(TAG, "showSoftKeyboard requested shown=" + shown + " focus=" + hasFocus());
          postDelayed(() -> {
            InputMethodManager retryImm =
                (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (retryImm != null) {
              retryImm.restartInput(this);
              boolean retryShown = retryImm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
              Log.d(TAG, "showSoftKeyboard retry shown=" + retryShown + " focus=" + hasFocus());
            }
          }, 80);
        }
        else {
          Log.w(TAG, "InputMethodManager unavailable");
        }
      });
    }

    public void hideSoftKeyboardFromNative() {
      post(() -> {
        InputMethodManager imm =
            (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
          imm.hideSoftInputFromWindow(getWindowToken(), 0);
          Log.d(TAG, "hideSoftKeyboard requested");
        }
      });
    }
  }

  private static final class AsepriteInputConnection extends BaseInputConnection {
    AsepriteInputConnection(View targetView) {
      super(targetView, false);
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
      sendCommittedText(text);
      return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
      return true;
    }

    @Override
    public boolean finishComposingText() {
      return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
      for (int i = 0; i < beforeLength; ++i) {
        sendKey(KeyEvent.KEYCODE_DEL, 0);
      }
      for (int i = 0; i < afterLength; ++i) {
        sendKey(KeyEvent.KEYCODE_FORWARD_DEL, 0);
      }
      return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
      int action = event.getAction();
      if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
        AsepriteBridge.nativeOnKey(
            action == KeyEvent.ACTION_DOWN ? TOUCH_DOWN : TOUCH_UP,
            event.getKeyCode(),
            event.getUnicodeChar(),
            event.getMetaState());
        return true;
      }
      return super.sendKeyEvent(event);
    }

    @Override
    public boolean performEditorAction(int editorAction) {
      sendKey(KeyEvent.KEYCODE_ENTER, 0);
      return true;
    }

    private void sendCommittedText(CharSequence text) {
      if (text == null) {
        return;
      }

      for (int offset = 0; offset < text.length();) {
        int codePoint = Character.codePointAt(text, offset);
        AsepriteBridge.nativeOnText(codePoint);
        offset += Character.charCount(codePoint);
      }
    }

    private void sendKey(int keyCode, int metaState) {
      AsepriteBridge.nativeOnKey(TOUCH_DOWN, keyCode, 0, metaState);
      AsepriteBridge.nativeOnKey(TOUCH_UP, keyCode, 0, metaState);
    }
  }
}
