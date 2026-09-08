package com.reader.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** A local test-only destination, using only platform classes in its separate process. */
public class QaQuoteReceiverActivity extends Activity {
  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    try {
      JSONObject payload = new JSONObject();
      payload.put("action", getIntent().getAction());
      payload.put("type", getIntent().getType());
      payload.put("text", getIntent().getStringExtra(Intent.EXTRA_TEXT));
      payload.put("subject", getIntent().getStringExtra(Intent.EXTRA_SUBJECT));
      payload.put("hasStream", getIntent().hasExtra(Intent.EXTRA_STREAM));
      try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "received-quote.json"))) {
        output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception error) {
      throw new IllegalStateException("Cannot record the local test quote", error);
    }
    finish();
  }
}
