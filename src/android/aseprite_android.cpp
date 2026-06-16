// Aseprite Android bridge
// Copyright (C) 2026  Igara Studio S.A.
//
// This program is distributed under the terms of
// the End-User License Agreement for Aseprite.

#ifdef HAVE_CONFIG_H
  #include "config.h"
#endif

#include "base/platform.h"
#include "os/event.h"
#include "os/event_queue.h"
#include "os/android/system.h"
#include "os/android/window.h"
#include "os/keys.h"
#include "ver/info.h"

#include <android/input.h>
#include <android/keycodes.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <condition_variable>
#include <exception>
#include <cstdlib>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>

extern int app_main(int argc, char* argv[]);

namespace {

constexpr const char* kLogTag = "AsepriteAndroid";

std::mutex g_appRunMutex;
std::mutex g_inputViewMutex;
JavaVM* g_javaVm = nullptr;
jobject g_inputView = nullptr;
jmethodID g_showSoftKeyboard = nullptr;
jmethodID g_hideSoftKeyboard = nullptr;
jmethodID g_showOpenDocumentPicker = nullptr;
jmethodID g_showCreateDocumentPicker = nullptr;
jmethodID g_finishCreateDocument = nullptr;
std::mutex g_fileDialogMutex;
std::condition_variable g_fileDialogCondition;
int g_nextFileDialogRequestId = 1;
std::map<int, std::vector<std::string>> g_fileDialogResults;
std::set<int> g_completedFileDialogs;

const char* archName()
{
  switch (base::Platform::arch) {
    case base::Platform::Arch::x86:     return "x86";
    case base::Platform::Arch::x64:     return "x64";
    case base::Platform::Arch::arm64:   return "arm64";
    case base::Platform::Arch::riscv64: return "riscv64";
  }
  return "unknown";
}

std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray array)
{
  std::vector<std::string> result;
  if (!array)
    return result;

  const jsize count = env->GetArrayLength(array);
  result.reserve(count);
  for (jsize i = 0; i < count; ++i) {
    auto str = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    if (!str) {
      result.emplace_back();
      continue;
    }

    const char* chars = env->GetStringUTFChars(str, nullptr);
    if (chars) {
      result.emplace_back(chars);
      env->ReleaseStringUTFChars(str, chars);
    }
    else {
      result.emplace_back();
    }
    env->DeleteLocalRef(str);
  }
  return result;
}

std::string toString(JNIEnv* env, jstring value)
{
  if (!value)
    return std::string();

  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars)
    return std::string();

  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

void setEnv(const char* name, const std::string& value)
{
  if (!value.empty())
    setenv(name, value.c_str(), 1);
}

int screenScaleForDensity(const float density)
{
  if (!(density > 0.0f))
    return 5;

  return std::clamp(static_cast<int>(density + 1.5f), 3, 6);
}

os::KeyModifiers modifiersFromAndroidMetaState(const int metaState)
{
  os::KeyModifiers modifiers = os::kKeyNoneModifier;
  if (metaState & AMETA_SHIFT_ON)
    modifiers |= os::kKeyShiftModifier;
  if (metaState & AMETA_CTRL_ON)
    modifiers |= os::kKeyCtrlModifier;
  if (metaState & AMETA_ALT_ON)
    modifiers |= os::kKeyAltModifier;
  if (metaState & AMETA_META_ON)
    modifiers |= os::kKeyCmdModifier;
  return modifiers;
}

os::KeyScancode scancodeFromAndroidKeyCode(const int keyCode)
{
  if (keyCode >= AKEYCODE_A && keyCode <= AKEYCODE_Z)
    return static_cast<os::KeyScancode>(os::kKeyA + (keyCode - AKEYCODE_A));
  if (keyCode >= AKEYCODE_0 && keyCode <= AKEYCODE_9)
    return static_cast<os::KeyScancode>(os::kKey0 + (keyCode - AKEYCODE_0));
  if (keyCode >= AKEYCODE_F1 && keyCode <= AKEYCODE_F12)
    return static_cast<os::KeyScancode>(os::kKeyF1 + (keyCode - AKEYCODE_F1));

  switch (keyCode) {
    case AKEYCODE_DEL:              return os::kKeyBackspace;
    case AKEYCODE_FORWARD_DEL:      return os::kKeyDel;
    case AKEYCODE_TAB:              return os::kKeyTab;
    case AKEYCODE_ENTER:            return os::kKeyEnter;
    case AKEYCODE_NUMPAD_ENTER:     return os::kKeyEnterPad;
    case AKEYCODE_SPACE:            return os::kKeySpace;
    case AKEYCODE_ESCAPE:           return os::kKeyEsc;
    case AKEYCODE_MOVE_HOME:        return os::kKeyHome;
    case AKEYCODE_MOVE_END:         return os::kKeyEnd;
    case AKEYCODE_PAGE_UP:          return os::kKeyPageUp;
    case AKEYCODE_PAGE_DOWN:        return os::kKeyPageDown;
    case AKEYCODE_DPAD_LEFT:        return os::kKeyLeft;
    case AKEYCODE_DPAD_RIGHT:       return os::kKeyRight;
    case AKEYCODE_DPAD_UP:          return os::kKeyUp;
    case AKEYCODE_DPAD_DOWN:        return os::kKeyDown;
    case AKEYCODE_MINUS:            return os::kKeyMinus;
    case AKEYCODE_EQUALS:           return os::kKeyEquals;
    case AKEYCODE_LEFT_BRACKET:     return os::kKeyOpenbrace;
    case AKEYCODE_RIGHT_BRACKET:    return os::kKeyClosebrace;
    case AKEYCODE_BACKSLASH:        return os::kKeyBackslash;
    case AKEYCODE_SEMICOLON:        return os::kKeyColon;
    case AKEYCODE_APOSTROPHE:       return os::kKeyQuote;
    case AKEYCODE_COMMA:            return os::kKeyComma;
    case AKEYCODE_PERIOD:           return os::kKeyStop;
    case AKEYCODE_SLASH:            return os::kKeySlash;
    case AKEYCODE_GRAVE:            return os::kKeyTilde;
    case AKEYCODE_NUMPAD_0:         return os::kKey0Pad;
    case AKEYCODE_NUMPAD_1:         return os::kKey1Pad;
    case AKEYCODE_NUMPAD_2:         return os::kKey2Pad;
    case AKEYCODE_NUMPAD_3:         return os::kKey3Pad;
    case AKEYCODE_NUMPAD_4:         return os::kKey4Pad;
    case AKEYCODE_NUMPAD_5:         return os::kKey5Pad;
    case AKEYCODE_NUMPAD_6:         return os::kKey6Pad;
    case AKEYCODE_NUMPAD_7:         return os::kKey7Pad;
    case AKEYCODE_NUMPAD_8:         return os::kKey8Pad;
    case AKEYCODE_NUMPAD_9:         return os::kKey9Pad;
    case AKEYCODE_NUMPAD_DIVIDE:    return os::kKeySlashPad;
    case AKEYCODE_NUMPAD_MULTIPLY:  return os::kKeyAsterisk;
    case AKEYCODE_NUMPAD_SUBTRACT:  return os::kKeyMinusPad;
    case AKEYCODE_NUMPAD_ADD:       return os::kKeyPlusPad;
    case AKEYCODE_NUMPAD_DOT:       return os::kKeyDelPad;
    case AKEYCODE_SHIFT_LEFT:       return os::kKeyLShift;
    case AKEYCODE_SHIFT_RIGHT:      return os::kKeyRShift;
    case AKEYCODE_CTRL_LEFT:        return os::kKeyLControl;
    case AKEYCODE_CTRL_RIGHT:       return os::kKeyRControl;
    case AKEYCODE_ALT_LEFT:         return os::kKeyAlt;
    case AKEYCODE_ALT_RIGHT:        return os::kKeyAltGr;
    case AKEYCODE_META_LEFT:        return os::kKeyLWin;
    case AKEYCODE_META_RIGHT:       return os::kKeyRWin;
    case AKEYCODE_MENU:             return os::kKeyMenu;
    default:                        return os::kKeyNil;
  }
}

void queueKeyEvent(const os::Event::Type type,
                   const os::KeyScancode scancode,
                   const os::KeyModifiers modifiers,
                   const base::codepoint_t unicodeChar)
{
  os::Event ev;
  ev.setType(type);
  ev.setScancode(scancode);
  ev.setModifiers(modifiers);
  ev.setUnicodeChar(unicodeChar);
  os::queue_event(ev);
}

void queueTextCodePoint(const base::codepoint_t codePoint)
{
  queueKeyEvent(os::Event::KeyDown, os::kKeyNil, os::kKeyNoneModifier, codePoint);
  queueKeyEvent(os::Event::KeyUp, os::kKeyNil, os::kKeyNoneModifier, 0);
}

JNIEnv* currentJniEnv()
{
  if (!g_javaVm)
    return nullptr;

  JNIEnv* env = nullptr;
  if (g_javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK)
    return env;

  if (g_javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK)
    return env;

  return nullptr;
}

void callInputViewMethod(const bool textInput)
{
  JNIEnv* env = currentJniEnv();
  if (!env) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "Cannot get JNI env for text input");
    return;
  }

  std::lock_guard<std::mutex> lock(g_inputViewMutex);
  if (!g_inputView) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "No input view for text input");
    return;
  }

  jmethodID method = textInput ? g_showSoftKeyboard : g_hideSoftKeyboard;
  if (method) {
    __android_log_print(ANDROID_LOG_INFO,
                        kLogTag,
                        "Text input %s requested",
                        textInput ? "show" : "hide");
    env->CallVoidMethod(g_inputView, method);
  }
  else {
    __android_log_print(ANDROID_LOG_WARN,
                        kLogTag,
                        "Missing text input method for %s",
                        textInput ? "show" : "hide");
  }
}

bool callOpenDocumentPicker(const int requestId, const bool allowMultiple)
{
  JNIEnv* env = currentJniEnv();
  if (!env) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "Cannot get JNI env for file picker");
    return false;
  }

  std::lock_guard<std::mutex> lock(g_inputViewMutex);
  if (!g_inputView || !g_showOpenDocumentPicker) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "No input view for file picker");
    return false;
  }

  __android_log_print(ANDROID_LOG_INFO,
                      kLogTag,
                      "Open document picker requested id=%d multiple=%d",
                      requestId,
                      allowMultiple ? 1 : 0);
  env->CallVoidMethod(g_inputView,
                      g_showOpenDocumentPicker,
                      static_cast<jint>(requestId),
                      static_cast<jboolean>(allowMultiple));
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  return true;
}

bool callCreateDocumentPicker(const int requestId,
                              const std::string& initialName,
                              const std::string& defaultExtension)
{
  JNIEnv* env = currentJniEnv();
  if (!env) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "Cannot get JNI env for create document");
    return false;
  }

  jstring initialNameString = env->NewStringUTF(initialName.c_str());
  jstring defaultExtensionString = env->NewStringUTF(defaultExtension.c_str());
  if (!initialNameString || !defaultExtensionString) {
    if (initialNameString)
      env->DeleteLocalRef(initialNameString);
    if (defaultExtensionString)
      env->DeleteLocalRef(defaultExtensionString);
    return false;
  }

  std::lock_guard<std::mutex> lock(g_inputViewMutex);
  if (!g_inputView || !g_showCreateDocumentPicker) {
    env->DeleteLocalRef(initialNameString);
    env->DeleteLocalRef(defaultExtensionString);
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "No input view for create document");
    return false;
  }

  __android_log_print(ANDROID_LOG_INFO,
                      kLogTag,
                      "Create document picker requested id=%d name=%s",
                      requestId,
                      initialName.c_str());
  env->CallVoidMethod(g_inputView,
                      g_showCreateDocumentPicker,
                      static_cast<jint>(requestId),
                      initialNameString,
                      defaultExtensionString);
  env->DeleteLocalRef(initialNameString);
  env->DeleteLocalRef(defaultExtensionString);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  return true;
}

bool callFinishCreateDocument(const std::string& localPath,
                              const bool success,
                              const bool keepMapping)
{
  JNIEnv* env = currentJniEnv();
  if (!env) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "Cannot get JNI env for finishing document");
    return false;
  }

  jstring localPathString = env->NewStringUTF(localPath.c_str());
  if (!localPathString)
    return false;

  std::lock_guard<std::mutex> lock(g_inputViewMutex);
  if (!g_inputView || !g_finishCreateDocument) {
    env->DeleteLocalRef(localPathString);
    return true;
  }

  const jboolean result = env->CallBooleanMethod(g_inputView,
                                                 g_finishCreateDocument,
                                                 localPathString,
                                                 static_cast<jboolean>(success),
                                                 static_cast<jboolean>(keepMapping));
  env->DeleteLocalRef(localPathString);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  return result == JNI_TRUE;
}

jstring newString(JNIEnv* env, const std::string& value)
{
  return env->NewStringUTF(value.c_str());
}

int runAppMain(std::vector<std::string>& argvStorage)
{
  std::vector<char*> argv;
  argv.reserve(argvStorage.size());
  for (std::string& arg : argvStorage)
    argv.push_back(arg.data());

  return app_main(static_cast<int>(argv.size()), argv.data());
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeBuildInfo(JNIEnv* env, jclass)
{
  const base::Platform platform = base::get_platform();
  std::string info = std::string(get_app_name()) + " " + get_app_version() +
                     " / Android API " + platform.osVer.str() + " / " + archName();
  return newString(env, info);
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeInitPaths(JNIEnv* env,
                                                         jclass,
                                                         jstring filesDir,
                                                         jstring cacheDir,
                                                         jstring documentsDir,
                                                         jfloat density)
{
  const std::string files = toString(env, filesDir);
  const std::string cache = toString(env, cacheDir);
  const std::string documents = toString(env, documentsDir);
  if (files.empty())
    return;

  const std::string config = files + "/config";
  const std::string user = config + "/aseprite";
  const std::string desktop = documents.empty() ? files + "/documents" : documents;

  setEnv("HOME", files);
  setEnv("XDG_CONFIG_HOME", config);
  setEnv("ASEPRITE_USER_FOLDER", user);
  setEnv("XDG_DESKTOP_DIR", desktop);
  setEnv("TMPDIR", cache.empty() ? files : cache);
  setEnv("TEMP", cache.empty() ? files : cache);
  setEnv("TMP", cache.empty() ? files : cache);
  setEnv("ASEPRITE_ANDROID_SCREEN_SCALE",
         std::to_string(screenScaleForDensity(static_cast<float>(density))));

  chdir(files.c_str());

  __android_log_print(ANDROID_LOG_INFO,
                      kLogTag,
                      "Initialized paths: files=%s cache=%s documents=%s density=%.2f scale=%s",
                      files.c_str(),
                      cache.c_str(),
                      desktop.c_str(),
                      static_cast<double>(density),
                      getenv("ASEPRITE_ANDROID_SCREEN_SCALE"));
}

extern "C" JNIEXPORT jint JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeRunCli(JNIEnv* env, jclass, jobjectArray args)
{
  std::unique_lock<std::mutex> lock(g_appRunMutex, std::try_to_lock);
  if (!lock.owns_lock()) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "Aseprite is already running");
    return -2;
  }

  std::vector<std::string> argvStorage;
  argvStorage.emplace_back("aseprite");

  std::vector<std::string> extraArgs = toStringVector(env, args);
  argvStorage.insert(argvStorage.end(), extraArgs.begin(), extraArgs.end());

  try {
    return runAppMain(argvStorage);
  }
  catch (const std::exception& e) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unhandled exception: %s", e.what());
  }
  catch (...) {
    __android_log_write(ANDROID_LOG_ERROR, kLogTag, "Unhandled non-standard exception");
  }

  return -1;
}

bool aseprite_android_show_open_file_dialog(const bool allowMultiple,
                                            std::vector<std::string>& output)
{
  int requestId = 0;
  {
    std::lock_guard<std::mutex> lock(g_fileDialogMutex);
    requestId = g_nextFileDialogRequestId++;
    g_fileDialogResults.erase(requestId);
    g_completedFileDialogs.erase(requestId);
  }

  if (!callOpenDocumentPicker(requestId, allowMultiple))
    return false;

  std::unique_lock<std::mutex> lock(g_fileDialogMutex);
  g_fileDialogCondition.wait(lock, [requestId] {
    return g_completedFileDialogs.find(requestId) != g_completedFileDialogs.end();
  });

  auto it = g_fileDialogResults.find(requestId);
  if (it != g_fileDialogResults.end()) {
    output = std::move(it->second);
    g_fileDialogResults.erase(it);
  }
  g_completedFileDialogs.erase(requestId);

  return !output.empty();
}

bool aseprite_android_show_save_file_dialog(const std::string& initialName,
                                            const std::string& defaultExtension,
                                            std::string& output)
{
  int requestId = 0;
  {
    std::lock_guard<std::mutex> lock(g_fileDialogMutex);
    requestId = g_nextFileDialogRequestId++;
    g_fileDialogResults.erase(requestId);
    g_completedFileDialogs.erase(requestId);
  }

  if (!callCreateDocumentPicker(requestId, initialName, defaultExtension))
    return false;

  std::unique_lock<std::mutex> lock(g_fileDialogMutex);
  g_fileDialogCondition.wait(lock, [requestId] {
    return g_completedFileDialogs.find(requestId) != g_completedFileDialogs.end();
  });

  auto it = g_fileDialogResults.find(requestId);
  if (it != g_fileDialogResults.end() && !it->second.empty()) {
    output = std::move(it->second.front());
    g_fileDialogResults.erase(it);
  }
  g_completedFileDialogs.erase(requestId);

  return !output.empty();
}

bool aseprite_android_finish_save_file_dialog(const std::string& localPath,
                                              const bool success,
                                              const bool keepMapping)
{
  return callFinishCreateDocument(localPath, success, keepMapping);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeStartGui(JNIEnv*, jclass)
{
  std::unique_lock<std::mutex> lock(g_appRunMutex, std::try_to_lock);
  if (!lock.owns_lock()) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, "Aseprite is already running");
    return 1;
  }

  try {
    std::thread([lock = std::move(lock)]() mutable {
      std::vector<std::string> argvStorage;
      argvStorage.emplace_back("aseprite");

      try {
        const int exitCode = runAppMain(argvStorage);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "GUI exited with code %d", exitCode);
      }
      catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "GUI exception: %s", e.what());
      }
      catch (...) {
        __android_log_write(ANDROID_LOG_ERROR, kLogTag, "GUI non-standard exception");
      }
    }).detach();
  }
  catch (const std::exception& e) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot start GUI thread: %s", e.what());
    return -1;
  }

  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeSetScreenSize(JNIEnv*, jclass, jint width, jint height)
{
  os::android_set_screen_size(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeSetSurface(JNIEnv* env, jclass, jobject surface)
{
  ANativeWindow* window = nullptr;
  if (surface)
    window = ANativeWindow_fromSurface(env, surface);

  os::android_set_native_window(window);

  if (window)
    ANativeWindow_release(window);
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeAttachInputView(JNIEnv* env, jclass, jobject view)
{
  std::lock_guard<std::mutex> lock(g_inputViewMutex);
  if (g_inputView) {
    env->DeleteGlobalRef(g_inputView);
    g_inputView = nullptr;
  }
  g_showSoftKeyboard = nullptr;
  g_hideSoftKeyboard = nullptr;
  g_showOpenDocumentPicker = nullptr;
  g_showCreateDocumentPicker = nullptr;
  g_finishCreateDocument = nullptr;

  if (!view)
    return;

  g_inputView = env->NewGlobalRef(view);
  jclass viewClass = env->GetObjectClass(view);
  g_showSoftKeyboard = env->GetMethodID(viewClass, "showSoftKeyboardFromNative", "()V");
  g_hideSoftKeyboard = env->GetMethodID(viewClass, "hideSoftKeyboardFromNative", "()V");
  g_showOpenDocumentPicker =
    env->GetMethodID(viewClass, "showOpenDocumentPickerFromNative", "(IZ)V");
  g_showCreateDocumentPicker =
    env->GetMethodID(viewClass,
                     "showCreateDocumentPickerFromNative",
                     "(ILjava/lang/String;Ljava/lang/String;)V");
  g_finishCreateDocument =
    env->GetMethodID(viewClass,
                     "finishCreateDocumentFromNative",
                     "(Ljava/lang/String;ZZ)Z");
  env->DeleteLocalRef(viewClass);
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeDetachInputView(JNIEnv* env, jclass)
{
  std::lock_guard<std::mutex> lock(g_inputViewMutex);
  if (g_inputView) {
    env->DeleteGlobalRef(g_inputView);
    g_inputView = nullptr;
  }
  g_showSoftKeyboard = nullptr;
  g_hideSoftKeyboard = nullptr;
  g_showOpenDocumentPicker = nullptr;
  g_showCreateDocumentPicker = nullptr;
  g_finishCreateDocument = nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnOpenDocumentResult(JNIEnv* env,
                                                                    jclass,
                                                                    jint requestId,
                                                                    jobjectArray paths)
{
  std::vector<std::string> result = toStringVector(env, paths);
  {
    std::lock_guard<std::mutex> lock(g_fileDialogMutex);
    g_fileDialogResults[static_cast<int>(requestId)] = std::move(result);
    g_completedFileDialogs.insert(static_cast<int>(requestId));
  }
  g_fileDialogCondition.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnSaveDocumentResult(JNIEnv* env,
                                                                    jclass,
                                                                    jint requestId,
                                                                    jstring path)
{
  std::vector<std::string> result;
  std::string localPath = toString(env, path);
  if (!localPath.empty())
    result.push_back(std::move(localPath));

  {
    std::lock_guard<std::mutex> lock(g_fileDialogMutex);
    g_fileDialogResults[static_cast<int>(requestId)] = std::move(result);
    g_completedFileDialogs.insert(static_cast<int>(requestId));
  }
  g_fileDialogCondition.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnPointer(JNIEnv*,
                                                         jclass,
                                                         jint action,
                                                         jfloat x,
                                                         jfloat y,
                                                         jfloat pressure,
                                                         jint button,
                                                         jint pointerType)
{
  os::android_queue_pointer_event(action,
                                  static_cast<float>(x),
                                  static_cast<float>(y),
                                  static_cast<float>(pressure),
                                  static_cast<int>(button),
                                  static_cast<int>(pointerType));
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnPointerDoubleClick(JNIEnv*,
                                                                    jclass,
                                                                    jfloat x,
                                                                    jfloat y,
                                                                    jfloat pressure,
                                                                    jint button,
                                                                    jint pointerType)
{
  os::android_queue_pointer_double_click_event(static_cast<float>(x),
                                               static_cast<float>(y),
                                               static_cast<float>(pressure),
                                               static_cast<int>(button),
                                               static_cast<int>(pointerType));
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnPointerWheel(JNIEnv*,
                                                              jclass,
                                                              jfloat x,
                                                              jfloat y,
                                                              jfloat dx,
                                                              jfloat dy,
                                                              jint pointerType,
                                                              jboolean precise)
{
  os::android_queue_pointer_wheel_event(static_cast<float>(x),
                                        static_cast<float>(y),
                                        static_cast<float>(dx),
                                        static_cast<float>(dy),
                                        static_cast<int>(pointerType),
                                        precise == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnTouchMagnify(JNIEnv*,
                                                              jclass,
                                                              jfloat x,
                                                              jfloat y,
                                                              jfloat magnification)
{
  os::android_queue_touch_magnify_event(static_cast<float>(x),
                                        static_cast<float>(y),
                                        static_cast<float>(magnification));
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnTouchPan(JNIEnv*,
                                                          jclass,
                                                          jfloat x,
                                                          jfloat y,
                                                          jfloat dx,
                                                          jfloat dy)
{
  os::android_queue_touch_pan_event(static_cast<float>(x),
                                    static_cast<float>(y),
                                    static_cast<float>(dx),
                                    static_cast<float>(dy));
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnText(JNIEnv*, jclass, jint codePoint)
{
  if (codePoint > 0)
    queueTextCodePoint(static_cast<base::codepoint_t>(codePoint));
}

extern "C" JNIEXPORT void JNICALL
Java_org_aseprite_android_AsepriteBridge_nativeOnKey(JNIEnv*,
                                                     jclass,
                                                     jint action,
                                                     jint keyCode,
                                                     jint unicodeChar,
                                                     jint metaState)
{
  const os::Event::Type type = (action == 0 ? os::Event::KeyDown : os::Event::KeyUp);
  queueKeyEvent(type,
                scancodeFromAndroidKeyCode(static_cast<int>(keyCode)),
                modifiersFromAndroidMetaState(static_cast<int>(metaState)),
                (type == os::Event::KeyDown ? static_cast<base::codepoint_t>(unicodeChar) : 0));
}

extern "C" void aseprite_android_set_text_input(const bool state)
{
  callInputViewMethod(state);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
  g_javaVm = vm;
  __android_log_write(ANDROID_LOG_INFO, kLogTag, "Loaded Aseprite Android bridge");
  return JNI_VERSION_1_6;
}
