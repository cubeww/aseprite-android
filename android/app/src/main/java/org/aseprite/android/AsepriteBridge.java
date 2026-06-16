package org.aseprite.android;

public final class AsepriteBridge {
  static {
    System.loadLibrary("aseprite-android");
  }

  private AsepriteBridge() {
  }

  public static native String nativeBuildInfo();
  public static native void nativeInitPaths(
      String filesDir, String cacheDir, String documentsDir, float density);
  public static native int nativeRunCli(String[] args);
  public static native int nativeStartGui();
  public static native void nativeSetScreenSize(int width, int height);
  public static native void nativeSetSurface(android.view.Surface surface);
  public static native void nativeAttachInputView(android.view.View view);
  public static native void nativeDetachInputView();
  public static native void nativeOnOpenDocumentResult(int requestId, String[] paths);
  public static native void nativeOnSaveDocumentResult(int requestId, String path);
  public static native void nativeOnPointer(
      int action, float x, float y, float pressure, int button, int pointerType);
  public static native void nativeOnPointerDoubleClick(
      float x, float y, float pressure, int button, int pointerType);
  public static native void nativeOnPointerWheel(
      float x, float y, float dx, float dy, int pointerType, boolean precise);
  public static native void nativeOnTouchMagnify(float x, float y, float magnification);
  public static native void nativeOnTouchPan(float x, float y, float dx, float dy);
  public static native void nativeOnText(int codePoint);
  public static native void nativeOnKey(int action, int keyCode, int unicodeChar, int metaState);
}
