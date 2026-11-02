// PTY transport for com.qapquiz.herdm.Pty: open a pty pair, fork/exec a
// shell with the slave as controlling terminal, and expose read/write/resize
// to Kotlin. The view/state machine stays in TerminalSession; this file only
// moves bytes.

#include <jni.h>

#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

std::vector<std::string> jstringArrayToVector(JNIEnv* env, jobjectArray array) {
  std::vector<std::string> out;
  if (array == nullptr) return out;
  const jsize len = env->GetArrayLength(array);
  out.reserve(static_cast<size_t>(len));
  for (jsize i = 0; i < len; i++) {
    auto str = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    if (str == nullptr) continue;
    const char* utf = env->GetStringUTFChars(str, nullptr);
    if (utf != nullptr) {
      out.emplace_back(utf);
      env->ReleaseStringUTFChars(str, utf);
    }
    env->DeleteLocalRef(str);
  }
  return out;
}

}  // namespace

extern "C" {

// Returns [pid, masterFd] as a 2-element jintArray, or null on failure.
JNIEXPORT jintArray JNICALL
Java_com_qapquiz_herdm_Pty_nativeOpen(
    JNIEnv* env, jobject, jint cols, jint rows, jstring shell, jobjectArray args,
    jstring cwd, jobjectArray envp) {
  const char* shellUtf = env->GetStringUTFChars(shell, nullptr);
  const char* cwdUtf = env->GetStringUTFChars(cwd, nullptr);
  if (shellUtf == nullptr || cwdUtf == nullptr) {
    if (shellUtf != nullptr) env->ReleaseStringUTFChars(shell, shellUtf);
    if (cwdUtf != nullptr) env->ReleaseStringUTFChars(cwd, cwdUtf);
    return nullptr;
  }

  int master = posix_openpt(O_RDWR | O_NOCTTY);
  if (master < 0) {
    env->ReleaseStringUTFChars(shell, shellUtf);
    env->ReleaseStringUTFChars(cwd, cwdUtf);
    return nullptr;
  }
  if (grantpt(master) != 0 || unlockpt(master) != 0) {
    close(master);
    env->ReleaseStringUTFChars(shell, shellUtf);
    env->ReleaseStringUTFChars(cwd, cwdUtf);
    return nullptr;
  }

  char slavePath[108];
  if (ptsname_r(master, slavePath, sizeof(slavePath)) != 0) {
    close(master);
    env->ReleaseStringUTFChars(shell, shellUtf);
    env->ReleaseStringUTFChars(cwd, cwdUtf);
    return nullptr;
  }

  // Master must not leak into the child's argv[0].. environment later on.
  const int masterFlags = fcntl(master, F_GETFD, 0);
  fcntl(master, F_SETFD, masterFlags | FD_CLOEXEC);

  const pid_t pid = fork();
  if (pid < 0) {
    close(master);
    env->ReleaseStringUTFChars(shell, shellUtf);
    env->ReleaseStringUTFChars(cwd, cwdUtf);
    return nullptr;
  }

  if (pid == 0) {
    // Child: new session, slave as controlling terminal, stdio wired up.
    setsid();
    const int slave = open(slavePath, O_RDWR);
    if (slave < 0) _exit(127);
    dup2(slave, STDIN_FILENO);
    dup2(slave, STDOUT_FILENO);
    dup2(slave, STDERR_FILENO);
    if (slave > STDERR_FILENO) close(slave);
    ioctl(STDIN_FILENO, TIOCSCTTY, 0);

    std::vector<std::string> argStrings = jstringArrayToVector(env, args);
    std::vector<char*> argv;
    argv.push_back(const_cast<char*>(shellUtf));
    for (auto& a : argStrings) argv.push_back(const_cast<char*>(a.c_str()));
    argv.push_back(nullptr);

    std::vector<std::string> envStrings = jstringArrayToVector(env, envp);
    std::vector<char*> envpPtrs;
    for (auto& e : envStrings) envpPtrs.push_back(const_cast<char*>(e.c_str()));
    envpPtrs.push_back(nullptr);

    if (chdir(cwdUtf) != 0) {
      // Non-fatal; shell starts wherever it can.
    }
    execve(shellUtf, argv.data(), envpPtrs.data());
    _exit(127);
  }

  env->ReleaseStringUTFChars(shell, shellUtf);
  env->ReleaseStringUTFChars(cwd, cwdUtf);

  winsize ws{};
  ws.ws_col = static_cast<unsigned short>(cols);
  ws.ws_row = static_cast<unsigned short>(rows);
  ioctl(master, TIOCSWINSZ, &ws);

  jintArray out = env->NewIntArray(2);
  if (out == nullptr) {
    close(master);
    kill(pid, SIGKILL);
    return nullptr;
  }
  const jint values[2] = {static_cast<jint>(pid), static_cast<jint>(master)};
  env->SetIntArrayRegion(out, 0, 2, values);
  return out;
}

// Blocking read of PTY output. Returns bytes read (0 = EOF), -1 on error.
JNIEXPORT jint JNICALL
Java_com_qapquiz_herdm_Pty_nativeRead(
    JNIEnv* env, jobject, jint fd, jbyteArray data) {
  if (fd < 0 || data == nullptr) return -1;
  const jsize cap = env->GetArrayLength(data);
  if (cap <= 0) return 0;
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  ssize_t n;
  do {
    n = read(fd, bytes, static_cast<size_t>(cap));
  } while (n < 0 && errno == EINTR);
  env->ReleaseByteArrayElements(data, bytes, 0);  // commit; reader reads before reuse
  if (n < 0) return -1;
  return static_cast<jint>(n);
}

// Writes all bytes; returns total written or -1 on error.
JNIEXPORT jint JNICALL
Java_com_qapquiz_herdm_Pty_nativeWrite(
    JNIEnv* env, jobject, jint fd, jbyteArray data) {
  if (fd < 0 || data == nullptr) return -1;
  const jsize len = env->GetArrayLength(data);
  if (len == 0) return 0;
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  size_t total = 0;
  while (total < static_cast<size_t>(len)) {
    ssize_t n = write(fd, bytes + total, static_cast<size_t>(len) - total);
    if (n < 0) {
      if (errno == EINTR) continue;
      total = 0;
      break;
    }
    total += static_cast<size_t>(n);
  }
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  return total > 0 ? static_cast<jint>(total) : -1;
}

// Sets the pty window size; the kernel delivers SIGWINCH to the foreground
// process group.
JNIEXPORT void JNICALL
Java_com_qapquiz_herdm_Pty_nativeResize(
    JNIEnv*, jobject, jint fd, jint cols, jint rows) {
  if (fd < 0) return;
  winsize ws{};
  ws.ws_col = static_cast<unsigned short>(cols);
  ws.ws_row = static_cast<unsigned short>(rows);
  ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_qapquiz_herdm_Pty_nativeSignal(
    JNIEnv*, jobject, jint pid, jint sig) {
  if (pid > 0) kill(static_cast<pid_t>(pid), sig);
}

// Blocking waitpid; returns the raw wait status (decode Kotlin-side).
JNIEXPORT jint JNICALL
Java_com_qapquiz_herdm_Pty_nativeWaitPid(
    JNIEnv*, jobject, jint pid) {
  if (pid <= 0) return -1;
  int status = 0;
  pid_t r;
  do {
    r = waitpid(static_cast<pid_t>(pid), &status, 0);
  } while (r < 0 && errno == EINTR);
  return r < 0 ? -1 : status;
}

JNIEXPORT void JNICALL
Java_com_qapquiz_herdm_Pty_nativeCloseFd(JNIEnv*, jobject, jint fd) {
  if (fd >= 0) close(fd);
}

}  // extern "C"
