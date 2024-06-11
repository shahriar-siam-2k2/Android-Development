package com.STL.fitcalc;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Objects;

public class about extends AppCompatActivity {

    ImageView mail, fb, insta, yt, git;
    Button back;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_about);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        mail = findViewById(R.id.gmail);
        fb = findViewById(R.id.fb);
        insta = findViewById(R.id.insta);
        yt = findViewById(R.id.youtube);
        git = findViewById(R.id.github);
        back = findViewById(R.id.back);

        mail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                redirect("mailto:shahriaraliseam@gmail.com");
            }
        });
        fb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                redirect("https://www.facebook.com/shahriarSiam2k2/");
            }
        });
        insta.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                redirect("https://www.instagram.com/me_the_shahriar/");
            }
        });
        yt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                redirect("https://www.youtube.com/@shahriaralisiam9621");
            }
        });
        git.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                redirect("https://github.com/shahriar-siam-2k2");
            }
        });
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(about.this, MainActivity.class);
                startActivity(i);
            }
        });
    }
        public void redirect(String url) {
            Uri uri = Uri.parse(url);
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(i);
        }
}