package com.STL.fitcalc;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Objects;

public class bmi extends AppCompatActivity {

    private EditText weightInput, heightFeetInput, heightInchInput;
    private TextView resultText, suggestionText;
    private Button resultBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_bmi);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        weightInput = findViewById(R.id.weightInput);
        heightFeetInput = findViewById(R.id.heightFeetInput);
        heightInchInput = findViewById(R.id.heightInchInput);
        resultText = findViewById(R.id.resultText);
        suggestionText = findViewById(R.id.suggestionText);
        resultBtn = findViewById(R.id.btnBMI);

        resultBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(weightInput.getText()) || TextUtils.isEmpty(heightFeetInput.getText()) || TextUtils.isEmpty(heightInchInput.getText())) {
                    showAlertDialog("Please fill out all fields.");
                }
                else {
                    double weight, feet, inch, height, bmi;

                    weight = Double.parseDouble(weightInput.getText().toString());
                    feet = Double.parseDouble(heightFeetInput.getText().toString());
                    inch = Double.parseDouble(heightInchInput.getText().toString());

                    height = (feet * 0.3048) + (inch * 0.0254); // converted to meter
                    bmi = weight / (height * height);

                    resultText.setText("Your BMI is " + String.format("%.2f", bmi));

                    if(bmi < 18.5){
                        suggestionText.setText("You are underweight. Gain weight by eating in moderation");
                    }
                    else if(bmi >= 18.5 && bmi <= 24.9){
                        suggestionText.setText("You are healthy.");
                    }
                    else if(bmi >= 25 && bmi <= 29.9){
                        suggestionText.setText("You are overweight. Exercise is necessary to lose excess weight.");
                    }
                    else if(bmi >= 30 && bmi <= 34.9){
                        suggestionText.setText("You are at first step of obese. A healthy diet and exercise is necessary.");
                    }
                    else if(bmi >= 35 && bmi <= 39.9){
                        suggestionText.setText("You are at second step of obese. Moderate diet and exercise are necessary.");
                    }
                    else if(bmi >= 40){
                        suggestionText.setText("You are extremely obese and at risk of death. Doctor's consultation is necessary.");
                    }
                }
            }
        });
    }

    private void showAlertDialog(String s) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Empty Fields")
                .setMessage(s)
                .setPositiveButton("CLOSE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                    }
                })
                .create();
        dialog.show();
    }
}