// Host smoke test for the vendored libghostty-vt: exercises the same API
// surface the Android JNI shim uses (terminal lifecycle, vt_write, resize,
// render-state snapshot basics, formatter, key encoder) and prints the
// formatted screen so a human can eyeball it. Build/run via host-smoke.sh.
#include <ghostty/vt.h>

#include <stdio.h>
#include <string.h>

static GhosttyTerminal term;

static void write(const char* s) {
  ghostty_terminal_vt_write(term, (const uint8_t*)s, strlen(s));
}

int main(void) {
  if (ghostty_terminal_new(NULL, &term, (GhosttyTerminalOptions){
                                              .cols = 40,
                                              .rows = 6,
                                              .max_scrollback = 1 << 20,
                                          }) != GHOSTTY_SUCCESS) {
    fprintf(stderr, "terminal_new failed\n");
    return 1;
  }

  write("ghostty on android\x1b[0m\r\n");
  write("\x1b[1;32mbold green\x1b[0m ");
  write("\x1b[3;94mitalic blue\x1b[0m\r\n");
  write("row3\r\nrow4\r\n");
  // Cursor positioning + erase
  write("\x1b[6;1Hbottom-line \x1b[2Kreset-line\r\n");

  // Scrollback: fill beyond the 6-row viewport.
  for (int i = 0; i < 20; i++) write("scrollback filler\r\n");

  // Resize reflow to a wider grid.
  if (ghostty_terminal_resize(term, 60, 8, 8, 16) != GHOSTTY_SUCCESS) {
    fprintf(stderr, "resize failed\n");
    return 1;
  }

  // Format the active screen as plain text.
  GhosttyFormatter formatter;
  GhosttyFormatterTerminalOptions opts = {
      .size = sizeof(GhosttyFormatterTerminalOptions),
      .emit = GHOSTTY_FORMATTER_FORMAT_PLAIN,
      .unwrap = true,
      .trim = true,
  };
  if (ghostty_formatter_terminal_new(NULL, &formatter, term, opts) != GHOSTTY_SUCCESS) {
    fprintf(stderr, "formatter_new failed\n");
    return 1;
  }

  uint8_t buf[8192];
  size_t written = 0;
  if (ghostty_formatter_format_buf(formatter, buf, sizeof(buf), &written) != GHOSTTY_SUCCESS) {
    fprintf(stderr, "format_buf failed\n");
    return 1;
  }
  printf("---- screen (%zu bytes) ----\n%.*s\n", written, (int)written, (char*)buf);

  if (!strstr((char*)buf, "bold green")) {
    fprintf(stderr, "FAIL: styled text missing from formatted screen\n");
    return 1;
  }

  // Key encoder: Enter under default modes must encode to CR (0x0d).
  GhosttyKeyEncoder encoder;
  GhosttyKeyEvent event;
  if (ghostty_key_encoder_new(NULL, &encoder) != GHOSTTY_SUCCESS ||
      ghostty_key_event_new(NULL, &event) != GHOSTTY_SUCCESS) {
    fprintf(stderr, "key encoder init failed\n");
    return 1;
  }
  ghostty_key_encoder_setopt_from_terminal(encoder, term);
  ghostty_key_event_set_action(event, GHOSTTY_KEY_ACTION_PRESS);
  ghostty_key_event_set_key(event, GHOSTTY_KEY_ENTER);
  uint8_t kbuf[32];
  size_t klen = 0;
  if (ghostty_key_encoder_encode(encoder, event, kbuf, sizeof(kbuf), &klen) != GHOSTTY_SUCCESS ||
      klen != 1 || kbuf[0] != 0x0d) {
    fprintf(stderr, "FAIL: Enter did not encode to CR (len=%zu)\n", klen);
    return 1;
  }

  ghostty_key_event_free(event);
  ghostty_key_encoder_free(encoder);
  ghostty_formatter_free(formatter);
  ghostty_terminal_free(term);
  printf("HOST SMOKE OK\n");
  return 0;
}
