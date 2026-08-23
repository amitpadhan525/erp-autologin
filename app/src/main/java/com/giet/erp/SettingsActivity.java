package com.giet.erp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

/**
 * Settings screen — enter your ERP Roll No and Password once.
 * The app handles everything else automatically on the phone.
 */
public class SettingsActivity extends AppCompatActivity {

    private TextInputEditText editUsername, editPassword;
    private Button btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("ERP Login Setup");
        }

        editUsername = findViewById(R.id.editUsername);
        editPassword = findViewById(R.id.editPassword);
        btnSave      = findViewById(R.id.btnSave);

        SharedPreferences prefs = getSharedPreferences("GIET_ERP_PREFS", MODE_PRIVATE);
        editUsername.setText(prefs.getString("username", ""));
        editPassword.setText(prefs.getString("password", ""));

        btnSave.setOnClickListener(v -> {
            String u = editUsername.getText() != null
                    ? editUsername.getText().toString().trim() : "";
            String p = editPassword.getText() != null
                    ? editPassword.getText().toString().trim() : "";

            if (u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this,
                        "Please enter both Roll No and Password",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.edit().putString("username", u).putString("password", p).apply();
            Toast.makeText(this, "✅ Saved! Auto-login will start now.",
                    Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
